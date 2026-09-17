import React, { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Button } from "@heroui/button";
import { Dropdown, DropdownTrigger, DropdownMenu, DropdownItem } from "@heroui/dropdown";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, useDisclosure } from "@heroui/modal";
import { Input } from "@heroui/input";
import { toast } from 'react-hot-toast';
import { copyTextToClipboard } from '@/utils/clipboard';

import { Logo } from '@/components/icons';
import { updatePassword, getVersionInfo } from '@/api';
import { safeLogout } from '@/utils/logout';
import { siteConfig, SITE_CONFIG_UPDATED } from '@/config/site';
import SkinPicker from '@/components/skin-picker';

interface MenuItem {
  path: string;
  label: string;
  icon: React.ReactNode;
  adminOnly?: boolean;
  /** 只给子账号(车友)看:管理员不显示,避免和管理页重复 */
  userOnly?: boolean;
}

interface PasswordForm {
  newUsername: string;
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export default function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const { isOpen, onOpen, onOpenChange } = useDisclosure();
  // 更新弹窗单独一个开关,别和改密码那个共用
  const updateModal = useDisclosure();

  const [isMobile, setIsMobile] = useState(false);
  const [mobileMenuVisible, setMobileMenuVisible] = useState(false);
  const [username, setUsername] = useState('');
  const [isAdmin, setIsAdmin] = useState(false);
  // 面板名:先用本地值渲染,后台校验到新名字时(SITE_CONFIG_UPDATED)实时刷新,
  // 免得管理员改了名字、车友那边一直显示旧名
  const [appName, setAppName] = useState(siteConfig.name);
  useEffect(() => {
    const onUpdated = () => setAppName(siteConfig.name);
    window.addEventListener(SITE_CONFIG_UPDATED, onUpdated);
    return () => window.removeEventListener(SITE_CONFIG_UPDATED, onUpdated);
  }, []);
  // 版本 / 更新提示。只给管理员看 —— 车友看到"有新版本"也没法更新,徒增困惑。
  // 后端拿构建时注入的 commit 跟 GitHub main 比,连不上 GitHub 时不提示(国内机常见)。
  const [versionInfo, setVersionInfo] = useState<any>(null);
  useEffect(() => {
    if (!isAdmin) return;
    getVersionInfo()
      .then((res) => { if (res.code === 0) setVersionInfo(res.data); })
      .catch(() => {});
  }, [isAdmin]);

  const [passwordLoading, setPasswordLoading] = useState(false);
  const [passwordForm, setPasswordForm] = useState<PasswordForm>({
    newUsername: '',
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  });

  // 菜单项配置
  const menuItems: MenuItem[] = [
    {
      path: '/dashboard',
      label: '仪表板',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" />
        </svg>
      ),
      // 仪表板是账号级口径(总流量/已用流量),车友那边一切按线路算,看了只会困惑 → 仅管理员
      adminOnly: true
    },
    {
      // 车友的主页面;管理员也留着——「我自己用」开的那条订阅在这里随时能找回来
      path: '/my-sub',
      label: '我的订阅',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z" clipRule="evenodd" />
        </svg>
      ),
    },
    {
      path: '/forward',
      label: '转发管理',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z" clipRule="evenodd" />
        </svg>
      ),
      // 转发是协议/中转的内部管道(每协议一条),车友不该看到、更不该删 → 仅管理员
      adminOnly: true
    },
    {
      path: '/inbound',
      label: '协议管理',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M10 1a1 1 0 011 1v1.323l3.954 1.582 1.599-.8a1 1 0 01.894 1.79l-1.233.616 1.738 5.42a1 1 0 01-.285 1.05A3.989 3.989 0 0115 15a3.989 3.989 0 01-2.667-1.019 1 1 0 01-.285-1.05l1.715-5.349L11 6.477V16h2a1 1 0 110 2H7a1 1 0 110-2h2V6.477L6.237 7.582l1.715 5.349a1 1 0 01-.285 1.05A3.989 3.989 0 015 15a3.989 3.989 0 01-2.667-1.019 1 1 0 01-.285-1.05l1.738-5.42-1.233-.616a1 1 0 01.894-1.79l1.599.8L9 3.323V2a1 1 0 011-1z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/relay',
      label: '中转',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M8 4a1 1 0 00-1 1v1H4a1 1 0 000 2h9.586l-1.293 1.293a1 1 0 101.414 1.414l3-3a1 1 0 000-1.414l-3-3A1 1 0 0013 4.586V5a1 1 0 00-1-1H8zm4 12a1 1 0 001-1v-1h3a1 1 0 100-2H6.414l1.293-1.293a1 1 0 10-1.414-1.414l-3 3a1 1 0 000 1.414l3 3A1 1 0 007 15.414V15a1 1 0 001 1h4z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/landing',
      label: '落地管理',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M4.083 9h1.946c.089-1.546.383-2.97.837-4.118A6.004 6.004 0 004.083 9zM10 2a8 8 0 100 16 8 8 0 000-16zm0 2c-.076 0-.232.032-.465.262-.238.234-.497.623-.737 1.182-.389.907-.673 2.142-.766 3.556h3.936c-.093-1.414-.377-2.649-.766-3.556-.24-.56-.5-.948-.737-1.182C10.232 4.032 10.076 4 10 4zm3.971 5c-.089-1.546-.383-2.97-.837-4.118A6.004 6.004 0 0115.917 9h-1.946zm-2.003 2H8.032c.093 1.414.377 2.649.766 3.556.24.56.5.948.737 1.182.233.23.389.262.465.262.076 0 .232-.032.465-.262.238-.234.498-.623.737-1.182.389-.907.673-2.142.766-3.556zm1.166 4.118c.454-1.147.748-2.572.837-4.118h1.946a6.004 6.004 0 01-2.783 4.118zm-6.268 0C6.412 13.97 6.118 12.546 6.03 11H4.083a6.004 6.004 0 002.783 4.118z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/tunnel',
      label: '隧道管理',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/node',
      label: '转发机',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M3 3a1 1 0 000 2v8a2 2 0 002 2h2.586l-1.293 1.293a1 1 0 101.414 1.414L10 15.414l2.293 2.293a1 1 0 001.414-1.414L12.414 15H15a2 2 0 002-2V5a1 1 0 100-2H3zm11.707 4.707a1 1 0 00-1.414-1.414L10 9.586 8.707 8.293a1 1 0 00-1.414 0l-2 2a1 1 0 101.414 1.414L8 10.414l1.293 1.293a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/limit',
      label: '限速管理',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/user',
      label: '用户管理',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M9 6a3 3 0 11-6 0 3 3 0 016 0zM17 6a3 3 0 11-6 0 3 3 0 016 0zM12.93 17c.046-.327.07-.66.07-1a6.97 6.97 0 00-1.5-4.33A5 5 0 0119 16v1h-6.07zM6 11a5 5 0 015 5v1H1v-1a5 5 0 015-5z" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/config',
      label: '网站配置',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/guide',
      label: '使用说明',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    }
  ];

  // 侧栏显示顺序(改这一行就能调顺序,不用挪上面那些带图标的大块)
  const MENU_ORDER = [
    '/dashboard',   // 0 仪表板
    '/my-sub',      // 车友专属,管理员看不到
    '/node',        // 1 转发机
    '/inbound',     // 2 协议管理
    '/relay',       // 3 中转
    '/landing',     // 3.5 落地(紧挨中转:搭完中转要改出口就来这)
    '/user',        // 4 用户
    '/limit',       // 5 限速
    '/tunnel',      // 6 隧道
    '/forward',     // 7 转发
    '/config',
    '/guide',
  ];
  menuItems.sort((a, b) => {
    const ia = MENU_ORDER.indexOf(a.path);
    const ib = MENU_ORDER.indexOf(b.path);
    return (ia === -1 ? 999 : ia) - (ib === -1 ? 999 : ib);
  });

  // 检查移动端
  const checkMobile = () => {
    setIsMobile(window.innerWidth <= 768);
    if (window.innerWidth > 768) {
      setMobileMenuVisible(false);
    }
  };

  useEffect(() => {
    // 获取用户信息
    const name = localStorage.getItem('name') || 'Admin';
    
    // 兼容处理：如果没有admin字段，根据role_id判断（0为管理员）
    let adminFlag = localStorage.getItem('admin') === 'true';
    if (localStorage.getItem('admin') === null) {
      const roleId = parseInt(localStorage.getItem('role_id') || '1', 10);
      adminFlag = roleId === 0;
      // 补充设置admin字段，避免下次再次判断
      localStorage.setItem('admin', adminFlag.toString());
    }
    
    setUsername(name);
    setIsAdmin(adminFlag);

    // 响应式检查
    checkMobile();
    window.addEventListener('resize', checkMobile);

    return () => {
      window.removeEventListener('resize', checkMobile);
    };
  }, []);

  // 退出登录
  const handleLogout = () => {
    safeLogout();
    navigate('/');
  };

  // 切换移动端菜单
  const toggleMobileMenu = () => {
    setMobileMenuVisible(!mobileMenuVisible);
  };

  // 隐藏移动端菜单
  const hideMobileMenu = () => {
    setMobileMenuVisible(false);
  };

  // 菜单点击处理
  const handleMenuClick = (path: string) => {
    navigate(path);
    if (isMobile) {
      hideMobileMenu();
    }
  };

  // 密码表单验证
  const validatePasswordForm = (): boolean => {
    if (!passwordForm.newUsername.trim()) {
      toast.error('请输入新用户名');
      return false;
    }
    if (passwordForm.newUsername.length < 3) {
      toast.error('用户名长度至少3位');
      return false;
    }
    if (!passwordForm.currentPassword) {
      toast.error('请输入当前密码');
      return false;
    }
    if (!passwordForm.newPassword) {
      toast.error('请输入新密码');
      return false;
    }
    if (passwordForm.newPassword.length < 6) {
      toast.error('新密码长度不能少于6位');
      return false;
    }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      toast.error('两次输入密码不一致');
      return false;
    }
    return true;
  };

  // 提交密码修改
  const handlePasswordSubmit = async () => {
    if (!validatePasswordForm()) return;

    setPasswordLoading(true);
    try {
      const response = await updatePassword(passwordForm);
      if (response.code === 0) {
        toast.success('密码修改成功，请重新登录');
        onOpenChange();
        handleLogout();
      } else {
        toast.error(response.msg || '密码修改失败');
      }
    } catch (error) {
      toast.error('修改密码时发生错误');
      console.error('修改密码错误:', error);
    } finally {
      setPasswordLoading(false);
    }
  };

  // 重置密码表单
  const resetPasswordForm = () => {
    setPasswordForm({
      newUsername: '',
      currentPassword: '',
      newPassword: '',
      confirmPassword: ''
    });
  };

  // 过滤菜单项（根据权限）:adminOnly 只给管理员,userOnly 只给车友
  const filteredMenuItems = menuItems.filter(item =>
    (!item.adminOnly || isAdmin) && (!item.userOnly || !isAdmin)
  );

  return (
          <div className={`flex ${isMobile ? 'min-h-screen' : 'h-screen'} bg-transparent`}>
      {/* 移动端遮罩层 */}
      {isMobile && mobileMenuVisible && (
        <div 
          className="fixed inset-0 backdrop-blur-sm bg-white/50 dark:bg-black/30 z-40"
          onClick={hideMobileMenu}
        />
      )}

      {/* 左侧菜单栏 */}
      <aside className={`
        ${isMobile ? 'fixed' : 'relative'} 
        ${isMobile && !mobileMenuVisible ? '-translate-x-full' : 'translate-x-0'}
        ${isMobile ? 'w-64' : 'w-72'}
        bg-white/70 dark:bg-black/40 backdrop-blur-xl
        shadow-lg
        border-r border-gray-200 dark:border-gray-600
        z-50 
        transition-transform duration-300 ease-in-out
        flex flex-col
        ${isMobile ? 'h-screen' : 'h-full'}
        ${isMobile ? 'top-0 left-0' : ''}
      `}>
                 {/* Logo 区域 */}
         <div className="px-3 py-3 h-14 flex items-center">
           <div className="flex items-center gap-2 w-full">
             <Logo size={24} />
             <div className="flex-1 min-w-0">
               <h1 className="text-sm font-bold text-foreground overflow-hidden whitespace-nowrap">{appName}</h1>
               <div className="flex items-center gap-1.5">
                 <p className="text-xs text-default-500">
                   v{versionInfo?.panelVersion || siteConfig.version}
                   {versionInfo?.commit && versionInfo.commit !== 'dev' && (
                     <span className="text-default-400">-{versionInfo.commit}</span>
                   )}
                 </p>
                 {versionInfo?.updateAvailable && (
                   <button
                     type="button"
                     onClick={updateModal.onOpen}
                     className="flex items-center gap-1 text-[10px] text-warning hover:opacity-70 transition-opacity"
                     title="点击查看怎么更新"
                   >
                     <span className="w-1.5 h-1.5 rounded-full bg-warning animate-pulse" />
                     有更新
                   </button>
                 )}
               </div>
             </div>
           </div>
         </div>

                 {/* 菜单导航 */}
         <nav className="flex-1 px-4 py-6 overflow-y-auto">
           <ul className="space-y-1">
            {filteredMenuItems.map((item) => {
              const isActive = location.pathname === item.path;
              return (
                <li key={item.path}>
                                     <button
                     onClick={() => handleMenuClick(item.path)}
                     className={`
                       w-full flex items-center gap-3 px-4 py-3 rounded-lg text-left
                       transition-colors duration-200 min-h-[44px]
                       ${isActive 
                         ? 'bg-primary-100 dark:bg-primary-600/20 text-primary-600 dark:text-primary-300' 
                         : 'text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-900'
                       }
                     `}
                   >
                     <div className="flex-shrink-0">
                       {item.icon}
                     </div>
                     <span className="font-medium text-sm">{item.label}</span>
                   </button>
                </li>
              );
            })}
          </ul>
        </nav>

                {/* 底部版权信息 */}
        <div className="px-4 py-2 pb-4 mt-auto flex-shrink-0">
          <div className="text-center">
            <p className="text-xs text-gray-400 dark:text-gray-500">
              Powered by <span className="text-gray-500 dark:text-gray-400">TMS</span>
            </p>
          </div>
        </div>
      </aside>

      {/* 主内容区域 */}
      <div className={`flex flex-col flex-1 ${isMobile ? 'min-h-0' : 'h-full overflow-hidden'}`}>
                 {/* 顶部导航栏 */}
         <header className="bg-white/60 dark:bg-black/30 backdrop-blur-xl shadow-md border-b border-gray-200 dark:border-gray-600 h-14 flex items-center justify-between px-4 lg:px-6 relative z-10">
          <div className="flex items-center gap-4">
            {/* 移动端菜单按钮 */}
            {isMobile && (
              <Button
                isIconOnly
                variant="light"
                onPress={toggleMobileMenu}
                className="lg:hidden"
              >
                <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
                </svg>
              </Button>
            )}
          </div>

          <div className="flex items-center gap-3">
            {/* 主题选择 */}
            <SkinPicker />
            {/* 用户菜单 */}
             <Dropdown placement="bottom-end">
               <DropdownTrigger>
                 <Button variant="light" className="text-sm font-medium text-foreground">
                   {username}
                   <svg className="w-4 h-4 ml-1" fill="currentColor" viewBox="0 0 20 20">
                     <path fillRule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clipRule="evenodd" />
                   </svg>
                 </Button>
               </DropdownTrigger>
              <DropdownMenu aria-label="用户菜单">
                <DropdownItem
                  key="change-password"
                  startContent={
                    <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v2H2v-4l4.257-4.257A6 6 0 1118 8zm-6-4a1 1 0 100 2 2 2 0 012 2 1 1 0 102 0 4 4 0 00-4-4z" clipRule="evenodd" />
                    </svg>
                  }
                  onPress={onOpen}
                >
                  修改密码
                </DropdownItem>
                <DropdownItem
                  key="logout"
                  startContent={
                    <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M3 3a1 1 0 00-1 1v12a1 1 0 102 0V4a1 1 0 00-1-1zm10.293 9.293a1 1 0 001.414 1.414l3-3a1 1 0 000-1.414l-3-3a1 1 0 10-1.414 1.414L14.586 9H7a1 1 0 100 2h7.586l-1.293 1.293z" clipRule="evenodd" />
                    </svg>
                  }
                  className="text-danger"
                  color="danger"
                  onPress={handleLogout}
                >
                  退出登录
                </DropdownItem>
              </DropdownMenu>
            </Dropdown>
          </div>
        </header>

        {/* 主内容 */}
        <main className={`flex-1 bg-transparent ${isMobile ? '' : 'overflow-y-auto'}`}>
          {children}
        </main>
      </div>

      {/* 修改密码弹窗 */}
      <Modal 
        isOpen={isOpen} 
        onOpenChange={() => {
          onOpenChange();
          resetPasswordForm();
        }}
        size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
      >
                 <ModalContent>
           {(onClose: () => void) => (
            <>
              <ModalHeader className="flex flex-col gap-1">修改密码</ModalHeader>
              <ModalBody>
                                 <div className="space-y-4">
                   <Input
                     label="新用户名"
                     placeholder="请输入新用户名（至少3位）"
                     value={passwordForm.newUsername}
                     onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, newUsername: e.target.value }))}
                     variant="bordered"
                   />
                   <Input
                     label="当前密码"
                     type="password"
                     placeholder="请输入当前密码"
                     value={passwordForm.currentPassword}
                     onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, currentPassword: e.target.value }))}
                     variant="bordered"
                   />
                   <Input
                     label="新密码"
                     type="password"
                     placeholder="请输入新密码（至少6位）"
                     value={passwordForm.newPassword}
                     onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, newPassword: e.target.value }))}
                     variant="bordered"
                   />
                   <Input
                     label="确认密码"
                     type="password"
                     placeholder="请再次输入新密码"
                     value={passwordForm.confirmPassword}
                     onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, confirmPassword: e.target.value }))}
                     variant="bordered"
                   />
                 </div>
              </ModalBody>
              <ModalFooter>
                <Button color="default" variant="light" onPress={onClose}>
                  取消
                </Button>
                <Button 
                  color="primary" 
                  onPress={handlePasswordSubmit}
                  isLoading={passwordLoading}
                >
                  确定
                </Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      {/* 更新说明弹窗。
          这里刻意不做「点一下自动更新」:面板跑在容器里,而 tms update 是宿主机
          命令(docker compose pull + up),容器内执行不了。要能执行只能把
          /var/run/docker.sock 挂进来 —— 那等于把宿主机 root 交给一个公网可访问
          的 Web 应用,面板一旦有 RCE 整台机器就没了。何况更新会重启 backend
          容器自己,执行更新的线程当场被杀,反而容易卡在半路。
          所以这里只把命令递到手边:告诉他敲什么、敲完会发生什么。 */}
      <Modal isOpen={updateModal.isOpen} onOpenChange={updateModal.onOpenChange} size="md">
        <ModalContent>
          {(onClose: () => void) => (
            <>
              <ModalHeader className="flex flex-col gap-1">发现新版本</ModalHeader>
              <ModalBody>
                <div className="space-y-4">
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-default-500">当前版本</span>
                    <span className="font-mono">
                      v{versionInfo?.panelVersion || siteConfig.version}
                      {versionInfo?.commit && versionInfo.commit !== 'dev' && `-${versionInfo.commit}`}
                    </span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-default-500">最新版本</span>
                    <span className="font-mono text-warning">{versionInfo?.latest || '-'}</span>
                  </div>

                  <div>
                    <p className="text-sm text-default-500 mb-2">在面板服务器上执行:</p>
                    <div className="flex items-center gap-2 bg-default-100 rounded-lg px-3 py-2">
                      <code className="flex-1 font-mono text-sm select-all">tms update</code>
                      <Button
                        size="sm"
                        variant="flat"
                        onPress={async () => {
                          (await copyTextToClipboard('tms update'))
                            ? toast.success('已复制')
                            : toast.error('复制失败,请手动选中命令');
                        }}
                      >
                        复制
                      </Button>
                    </div>
                  </div>

                  <div className="text-xs text-default-500 space-y-1">
                    <p>· 更新过程面板会重启,大约 1-2 分钟</p>
                    <p>· 节点和转发跑在各自的机器上,不受面板重启影响</p>
                    <p>· 车友的订阅链接不变,不用重新分发</p>
                  </div>
                </div>
              </ModalBody>
              <ModalFooter>
                <Button color="primary" onPress={onClose}>知道了</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
} 