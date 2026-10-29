#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
老版本面板留下的「库和节点对不上」脏状态体检 —— 只读,只报告,不改任何东西。

数据库只跑 SELECT;节点那边读的是节点自己落盘的 gost.json(节点程序工作目录下,
每次增删改服务都会重写),需要先拷到本机。

用法(在面板机上):
  python3 check_dirty_state.py \
      --mysql "docker exec -i gost-mysql mysql -ugost -p密码 gost" \
      --gost 1=/tmp/node1-gost.json --gost 2=/tmp/node2-gost.json
  --mysql  一条能从标准输入读 SQL、按制表符输出结果的命令(mysql 客户端默认就是)
  --gost   节点ID=该节点的 gost.json 路径,可重复;没给的节点跳过、不判

报告四类(都是老版本可能留下的):
  ① 库里转发已停(status=0),节点上服务还在跑(没暂停)
  ② 转发 status=-1(下发失败),或节点上实际挂的限速器和库里该挂的不一样
  ③ 同一条转发在节点上有多份服务(比如暂停的 _0 和跑着的 _{隧道权限id} 同端口并存)
  ④ 孤儿:没有服务引用的链 / 库里已没有这条转发的服务和链
"""
import argparse
import json
import re
import shlex
import subprocess
import sys
from collections import defaultdict

SVC_RE = re.compile(r'^(\d+)_(\d+)_(\d+)_(tcp|udp|tls)$')
CHAIN_RE = re.compile(r'^(\d+)_(\d+)_(\d+)_chains$')


def run_sql(cmd, sql):
    p = subprocess.run(shlex.split(cmd), input=sql.encode('utf-8'),
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        sys.exit('查库失败: ' + p.stderr.decode('utf-8', 'replace'))
    lines = [l for l in p.stdout.decode('utf-8', 'replace').splitlines() if l.strip()]
    if not lines:
        return []
    head = lines[0].split('\t')
    rows = []
    for l in lines[1:]:
        vals = [None if v == 'NULL' else v for v in l.split('\t')]
        rows.append(dict(zip(head, vals)))
    return rows


def to_int(v):
    return None if v is None else int(v)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--mysql', required=True)
    ap.add_argument('--gost', action='append', default=[], help='节点ID=gost.json 路径')
    a = ap.parse_args()

    forwards = run_sql(a.mysql, "SELECT f.id, f.user_id, f.name, f.tunnel_id, f.status, f.speed_id, f.in_port,"
                                " t.type, t.in_node_id, t.out_node_id FROM forward f LEFT JOIN tunnel t ON t.id = f.tunnel_id;")
    uts = run_sql(a.mysql, "SELECT id, user_id, tunnel_id, speed_id FROM user_tunnel;")
    protocol_ids = {to_int(r['gost_forward_id']) for r in
                    run_sql(a.mysql, "SELECT gost_forward_id FROM inbound_user WHERE gost_forward_id IS NOT NULL;")}

    ut_by = {(to_int(r['user_id']), to_int(r['tunnel_id'])): r for r in uts}
    fwd_by_id = {to_int(f['id']): f for f in forwards}

    # 节点上的服务/链,按节点分
    nodes = {}
    for g in a.gost:
        nid, path = g.split('=', 1)
        with open(path, encoding='utf-8') as fh:
            nodes[int(nid)] = json.load(fh)

    svc = {}      # (node, name) -> service dict
    groups = defaultdict(lambda: defaultdict(list))  # node -> (fid,uid) -> [(ut, suffix, service)]
    chains = {}   # (node, name) -> chain
    used_chains = defaultdict(set)
    for nid, cfg in nodes.items():
        for s in cfg.get('services') or []:
            svc[(nid, s['name'])] = s
            m = SVC_RE.match(s['name'])
            if m:
                groups[nid][(int(m.group(1)), int(m.group(2)))].append((int(m.group(3)), m.group(4), s))
            ch = (s.get('handler') or {}).get('chain')
            if ch:
                used_chains[nid].add(ch)
        for c in cfg.get('chains') or []:
            chains[(nid, c['name'])] = c

    def paused(s):
        return bool((s.get('metadata') or {}).get('paused'))

    out = defaultdict(list)

    for f in forwards:
        fid, uid = to_int(f['id']), to_int(f['user_id'])
        status = to_int(f['status'])
        in_node, out_node = to_int(f['in_node_id']), to_int(f['out_node_id'])
        ttype = to_int(f['type'])
        tag = '转发[%s %s 端口%s]' % (fid, f['name'], f['in_port'])
        is_protocol = fid in protocol_ids
        ut = ut_by.get((uid, to_int(f['tunnel_id'])))
        if f['speed_id'] is not None:
            want_limiter = f['speed_id']
        elif not is_protocol and ut is not None and ut['speed_id'] is not None:
            want_limiter = ut['speed_id']
        else:
            want_limiter = None

        if in_node not in nodes:
            continue
        mine = groups[in_node].get((fid, uid), [])
        main = [(u, sfx, s) for (u, sfx, s) in mine if sfx in ('tcp', 'udp')]
        variants = sorted({u for (u, _, _) in main})

        # ① 库里停了,节点上还在跑
        if status == 0:
            running = [s['name'] for (_, _, s) in main if not paused(s)]
            if running:
                out['①'].append('%s 库里已停,节点%s 上还在跑: %s' % (tag, in_node, ' '.join(running)))
            if ttype == 2 and out_node in nodes:
                remote = [s['name'] for (n, s) in svc.items() if n[0] == out_node
                          and SVC_RE.match(s['name']) and s['name'].startswith('%s_%s_' % (fid, uid))
                          and s['name'].endswith('_tls') and not paused(s)]
                if remote:
                    out['①'].append('%s 库里已停,出口节点%s 上远端还在跑: %s' % (tag, out_node, ' '.join(remote)))

        # ② -1 / 限速对不上(只看跑着的)
        if status == -1:
            desc = ', '.join('%s(limiter=%s,%s)' % (s['name'], s.get('limiter'), '暂停' if paused(s) else '运行')
                             for (_, _, s) in main) or '节点上没有它的服务'
            out['②'].append('%s status=-1(下发失败),节点%s: %s;库里该挂限速器=%s' % (tag, in_node, desc, want_limiter))
        for (u, sfx, s) in main:
            if paused(s):
                continue
            have = s.get('limiter')
            if str(have or '') != str(want_limiter or ''):
                out['②'].append('%s 节点%s 上 %s 挂的限速器=%s,库里该挂=%s%s' % (
                    tag, in_node, s['name'], have, want_limiter,
                    '(协议转发只认转发自己的限速)' if is_protocol else ''))

        # ③ 多份服务
        if len(variants) > 1:
            desc = ', '.join('%s(%s)' % (s['name'], '暂停' if paused(s) else '运行')
                             for (_, _, s) in sorted(main, key=lambda x: x[2]['name']))
            out['③'].append('%s 节点%s 上同时有 %d 份服务: %s' % (tag, in_node, len(variants), desc))

    # ④ 孤儿
    for (nid, name), c in sorted(chains.items()):
        m = CHAIN_RE.match(name)
        if name not in used_chains[nid]:
            out['④'].append('节点%s 链 %s 没有任何服务在用(孤儿链)' % (nid, name))
        elif m and int(m.group(1)) not in fwd_by_id:
            out['④'].append('节点%s 链 %s 对应的转发 %s 库里已没有' % (nid, name, m.group(1)))
    for (nid, name), s in sorted(svc.items()):
        m = SVC_RE.match(name)
        if m and int(m.group(1)) not in fwd_by_id:
            out['④'].append('节点%s 服务 %s(%s,%s)对应的转发 %s 库里已没有' % (
                nid, name, s.get('addr'), '暂停' if paused(s) else '运行', m.group(1)))

    titles = {'①': '库里已停、节点还在跑', '②': '下发失败 / 限速对不上', '③': '同一转发多份服务', '④': '孤儿链 / 孤儿服务'}
    total = 0
    for k in ('①', '②', '③', '④'):
        items = out.get(k, [])
        total += len(items)
        print('%s %s:%d 条' % (k, titles[k], len(items)))
        for it in items:
            print('    ' + it)
    print('共 %d 条(只读检查,没有改任何数据)' % total)


if __name__ == '__main__':
    main()
