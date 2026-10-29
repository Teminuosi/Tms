package com.admin.service.impl;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.GostDeleteFallback;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.Md5Util;
import com.admin.entity.*;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 用户服务实现类
 * 提供用户的增删改查功能，包括用户登录、创建、更新、删除和套餐信息查询
 * 支持用户关联数据的级联删除，包括转发和Gost服务的清理
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    // ========== 常量定义 ==========
    
    /** 用户角色常量 */
    private static final int ADMIN_ROLE_ID = 0;        // 管理员角色ID
    private static final int USER_ROLE_ID = 1;         // 普通用户角色ID
    
    /** 用户状态常量 */
    private static final int USER_STATUS_ACTIVE = 1;   // 用户启用状态
    private static final int USER_STATUS_DISABLED = 0; // 用户停用状态
    
    /** 隧道类型常量 */
    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2; // 隧道转发类型
    
    /** 成功响应消息 */
    private static final String SUCCESS_CREATE_MSG = "用户创建成功";
    private static final String SUCCESS_UPDATE_MSG = "用户更新成功";
    private static final String SUCCESS_DELETE_MSG = "用户及关联数据删除成功";
    
    /** 错误响应消息 */
    private static final String ERROR_LOGIN_CREDENTIALS = "账号或密码错误";
    private static final String ERROR_ACCOUNT_DISABLED = "账户停用";
    private static final String ERROR_CREATE_FAILED = "用户创建失败";
    private static final String ERROR_UPDATE_FAILED = "用户更新失败";
    private static final String ERROR_DELETE_FAILED = "用户删除失败";
    private static final String ERROR_USER_NOT_FOUND = "用户不存在";
    private static final String ERROR_USERNAME_EXISTS = "用户名已存在";
    private static final String ERROR_USERNAME_TAKEN = "用户名已被其他用户使用";
    private static final String ERROR_CANNOT_DELETE_ADMIN = "不能删除管理员用户";
    private static final String ERROR_CANNOT_UPDATE_ADMIN = "不能修改管理员用户信息";
    private static final String ERROR_USER_NOT_LOGGED_IN = "用户未登录或token无效";
    private static final String ERROR_GET_PACKAGE_INFO_FAILED = "获取套餐信息失败";
    private static final String ERROR_CURRENT_PASSWORD_WRONG = "当前密码错误";
    private static final String ERROR_PASSWORD_NOT_MATCH = "新密码和确认密码不匹配";

    /** 默认账号密码 */
    private static final String DEFAULT_USERNAME = "admin_user";
    private static final String DEFAULT_PASSWORD = "admin_user";
    
    /** 登录响应字段名 */
    private static final String LOGIN_TOKEN_FIELD = "token";
    private static final String LOGIN_NAME_FIELD = "name";
    private static final String LOGIN_ROLE_ID_FIELD = "role_id";
    private static final String LOGIN_REQUIRE_PASSWORD_CHANGE_FIELD = "requirePasswordChange";

    // ========== 依赖注入 ==========
    
    @Resource
    private UserMapper userMapper;
    
    @Resource
    @Lazy
    private ForwardMapper forwardMapper;
    
    @Resource
    private UserTunnelMapper userTunnelMapper;

    @Resource
    @Lazy
    private com.admin.mapper.InboundUserMapper inboundUserMapper;

    @Resource
    @Lazy
    private com.admin.mapper.InboundLineMapper inboundLineMapper;
    
    @Resource
    @Lazy
    private TunnelService tunnelService;
    
    @Resource
    @Lazy
    private NodeService nodeService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    ViteConfigService viteConfigService;

    @Resource
    StatisticsFlowService statisticsFlowService;

    @Resource
    private ImageCaptchaApplication application;

    // ========== 公共接口实现 ==========

    /**
     * 用户登录
     * 验证验证码、用户名密码，检查账户状态，生成JWT令牌
     * 
     * @param loginDto 登录数据传输对象
     * @return 登录结果响应，包含令牌和用户信息
     */
    @Override
    public R login(LoginDto loginDto) {

        // 1. 验证验证码
        ViteConfig viteConfig = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", "captcha_enabled"));
        if (viteConfig != null && Objects.equals(viteConfig.getValue(), "true")) {
            if (StringUtils.isBlank(loginDto.getCaptchaId())) return R.err("验证码校验失败");
            boolean valid = ((SecondaryVerificationApplication) application).secondaryVerification(loginDto.getCaptchaId());
            if (!valid)  return R.err("验证码校验失败");
        }



        // 2. 验证用户凭据
        LoginValidationResult validationResult = validateUserCredentials(loginDto);
        if (validationResult.isHasError()) {
            return R.err(validationResult.getErrorMessage());
        }

        // 3. 生成令牌并返回用户信息
        User user = validationResult.getUser();
        String token = JwtUtil.generateToken(user);
        
        // 4. 检查是否使用默认账号密码
        boolean requirePasswordChange = isDefaultCredentials(loginDto.getUsername(), loginDto.getPassword());
        
        return R.ok(MapUtil.builder()
                .put(LOGIN_TOKEN_FIELD, token)
                .put(LOGIN_NAME_FIELD, user.getUser())
                .put(LOGIN_ROLE_ID_FIELD, user.getRoleId())
                .put(LOGIN_REQUIRE_PASSWORD_CHANGE_FIELD, requirePasswordChange)
                .build());
    }

    /**
     * 创建用户
     * 检查用户名唯一性，设置默认属性，加密密码
     * 
     * @param userDto 用户创建数据传输对象
     * @return 创建结果响应
     */
    @Override
    public R createUser(UserDto userDto) {
        // 1. 验证用户名唯一性
        R usernameValidationResult = validateUsernameUniqueness(userDto.getUser(), null);
        if (usernameValidationResult.getCode() != 0) {
            return usernameValidationResult;
        }

        // 2. 构建用户实体并保存
        User user = buildNewUserEntity(userDto);
        boolean result = this.save(user);
        
        if (result) {
            // 3. 添加到期时间延时任务
            return R.ok(SUCCESS_CREATE_MSG);
        } else {
            return R.err(ERROR_CREATE_FAILED);
        }
    }

    /**
     * 获取所有用户（分页）
     * 支持关键字搜索，排除管理员用户，清除密码信息
     *
     * @return 分页用户列表响应
     */
    @Override
    public R getAllUsers() {
        return R.ok(this.list(new QueryWrapper<User>().ne("role_id", ADMIN_ROLE_ID)));
    }

    /**
     * 更新用户信息
     * 验证用户存在性和用户名唯一性，处理密码加密
     * 
     * @param userUpdateDto 用户更新数据传输对象
     * @return 更新结果响应
     */
    @Override
    public R updateUser(UserUpdateDto userUpdateDto) {
        // 1. 验证用户是否存在
        if (!isUserExists(userUpdateDto.getId())) {
            return R.err(ERROR_USER_NOT_FOUND);
        }

        // 2. 验证用户名唯一性
        R usernameValidationResult = validateUsernameUniqueness(userUpdateDto.getUser(), userUpdateDto.getId());
        if (usernameValidationResult.getCode() != 0) {
            return usernameValidationResult;
        }

        // 3. 验证更新条件
        R updateValidationResult = validateUserUpdate(userUpdateDto.getId());
        if (updateValidationResult.getCode() != 0) {
            return updateValidationResult;
        }

        // 4. 构建更新实体并保存
        User updateUser = buildUpdateUserEntity(userUpdateDto);
        boolean result = this.updateById(updateUser);
        
        if (result) {
            // 5. 处理到期时间延时任务
            return R.ok(SUCCESS_UPDATE_MSG);
        } else {
            return R.err(ERROR_UPDATE_FAILED);
        }
    }

    /**
     * 删除用户
     * 级联删除用户相关的所有数据，包括转发、Gost服务和隧道权限
     * 
     * @param id 用户ID
     * @return 删除结果响应
     */
    @Override
    public R deleteUser(Long id) {
        // 1. 验证删除条件
        R deleteValidationResult = validateUserDeletion(id);
        if (deleteValidationResult.getCode() != 0) {
            return deleteValidationResult;
        }

        try {
            // 2. 级联删除用户相关数据
            deleteUserRelatedData(id);
            statisticsFlowService.remove(new QueryWrapper<StatisticsFlow>().eq("user_id", id));
            // 3. 删除用户
            boolean result = this.removeById(id);
            return result ? R.ok(SUCCESS_DELETE_MSG) : R.err(ERROR_DELETE_FAILED);
            
        } catch (Exception e) {
            e.printStackTrace();
            return R.err("删除用户时发生错误：" + e.getMessage());
        }
    }

    /**
     * 获取用户套餐信息
     * 包括用户基本信息、隧道权限详情和转发详情
     * 
     * @return 用户套餐信息响应
     */
    @Override
    public R getUserPackageInfo() {
        try {
                    // 1. 获取当前用户信息
        CurrentUserInfo currentUser = getCurrentUserInfo();
        if (currentUser.isHasError()) {
            return R.err(currentUser.getErrorMessage());
        }

            // 2. 构建套餐信息
            UserPackageDto packageDto = buildUserPackageDto(currentUser);
            
            return R.ok(packageDto);
        } catch (Exception e) {
            e.printStackTrace();
            return R.err(ERROR_GET_PACKAGE_INFO_FAILED);
        }
    }

    /**
     * 修改账号密码
     * 验证当前密码、新密码确认、用户名唯一性、更新用户账号密码
     * 
     * @param changePasswordDto 修改账号密码数据传输对象
     * @return 修改结果响应
     */
    @Override
    public R updatePassword(ChangePasswordDto changePasswordDto) {
        try {
            // 1. 获取当前用户信息
            CurrentUserInfo currentUser = getCurrentUserInfo();
            if (currentUser.isHasError()) {
                return R.err(currentUser.getErrorMessage());
            }

            // 2. 验证新密码和确认密码是否匹配
            if (!changePasswordDto.getNewPassword().equals(changePasswordDto.getConfirmPassword())) {
                return R.err(ERROR_PASSWORD_NOT_MATCH);
            }

            // 3. 验证当前密码是否正确
            User user = currentUser.getUser();
            String currentPasswordMd5 = Md5Util.md5(changePasswordDto.getCurrentPassword());
            if (!user.getPwd().equals(currentPasswordMd5)) {
                return R.err(ERROR_CURRENT_PASSWORD_WRONG);
            }

            // 4. 验证新用户名唯一性（如果与当前用户名不同）
            if (!user.getUser().equals(changePasswordDto.getNewUsername())) {
                R usernameValidationResult = validateUsernameUniqueness(changePasswordDto.getNewUsername(), user.getId());
                if (usernameValidationResult.getCode() != 0) {
                    return usernameValidationResult;
                }
            }

            // 5. 更新用户名和密码
            User updateUser = new User();
            updateUser.setId(user.getId());
            updateUser.setUser(changePasswordDto.getNewUsername());
            updateUser.setPwd(Md5Util.md5(changePasswordDto.getNewPassword()));
            updateUser.setUpdatedTime(System.currentTimeMillis());
            
            boolean result = this.updateById(updateUser);
            return result ? R.ok("账号密码修改成功") : R.err(ERROR_UPDATE_FAILED);
            
        } catch (Exception e) {
            e.printStackTrace();
            return R.err("修改账号密码时发生错误：" + e.getMessage());
        }
    }

    @Override
    public R reset(ResetFlowDto resetFlowDto) {
        if (resetFlowDto.getType() == 1){ // 清零账号流量
            User user = this.getById(resetFlowDto.getId());
            if (user == null) return R.err(ERROR_USER_NOT_FOUND);
            user.setInFlow(0L);
            user.setOutFlow(0L);
            this.updateById(user);
        }else { // 清零隧道流量
            UserTunnel tunnel = userTunnelService.getById(resetFlowDto.getId());
            if (tunnel == null) return R.err("隧道不存在");
            tunnel.setInFlow(0L);
            tunnel.setOutFlow(0L);
            userTunnelService.updateById(tunnel);
        }
        return R.ok();
    }

    // ========== 私有辅助方法 ==========


    /**
     * 验证用户登录凭据
     * 
     * @param loginDto 登录数据传输对象
     * @return 登录验证结果
     */
    private LoginValidationResult validateUserCredentials(LoginDto loginDto) {
        User user = this.getOne(new QueryWrapper<User>().eq("user", loginDto.getUsername()));
        if (user == null) {
            return LoginValidationResult.error(ERROR_LOGIN_CREDENTIALS);
        }
        
        if (!user.getPwd().equals(Md5Util.md5(loginDto.getPassword()))) {
            return LoginValidationResult.error(ERROR_LOGIN_CREDENTIALS);
        }
        
        if (user.getStatus() == USER_STATUS_DISABLED) {
            return LoginValidationResult.error(ERROR_ACCOUNT_DISABLED);
        }
        
        return LoginValidationResult.success(user);
    }

    /**
     * 检查是否使用默认账号密码
     * 
     * @param username 用户名
     * @param password 密码
     * @return 是否是默认凭据
     */
    private boolean isDefaultCredentials(String username, String password) {
        return DEFAULT_USERNAME.equals(username) || DEFAULT_PASSWORD.equals(password);
    }

    /**
     * 验证用户名唯一性
     * 
     * @param username 用户名
     * @param excludeUserId 排除的用户ID（用于更新时排除自己）
     * @return 验证结果响应
     */
    private R validateUsernameUniqueness(String username, Long excludeUserId) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<User>().eq("user", username);
        if (excludeUserId != null) {
            queryWrapper.ne("id", excludeUserId);
        }
        
        User existUser = this.getOne(queryWrapper);
        if (existUser != null) {
            String errorMsg = excludeUserId != null ? ERROR_USERNAME_TAKEN : ERROR_USERNAME_EXISTS;
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 构建新用户实体对象
     * 
     * @param userDto 用户创建DTO
     * @return 构建完成的用户对象
     */
    private User buildNewUserEntity(UserDto userDto) {
        User user = new User();
        BeanUtils.copyProperties(userDto, user);
        
        // 设置加密密码
        user.setPwd(Md5Util.md5(userDto.getPwd()));
        
        // 设置默认属性
        user.setStatus(userDto.getStatus() != null ? userDto.getStatus() : USER_STATUS_ACTIVE);
        user.setRoleId(USER_ROLE_ID);
        
        // 设置时间戳
        long currentTime = System.currentTimeMillis();
        user.setCreatedTime(currentTime);
        user.setUpdatedTime(currentTime);
        
        return user;
    }



    /**
     * 检查用户是否存在
     * 
     * @param userId 用户ID
     * @return 用户是否存在
     */
    private boolean isUserExists(Long userId) {
        return this.getById(userId) != null;
    }

    /**
     * 构建用户更新实体对象
     * 
     * @param userUpdateDto 用户更新DTO
     * @return 构建完成的更新对象
     */
    private User buildUpdateUserEntity(UserUpdateDto userUpdateDto) {
        User user = new User();
        BeanUtils.copyProperties(userUpdateDto, user);
        
        // 处理密码更新
        if (StrUtil.isNotBlank(userUpdateDto.getPwd())) {
            user.setPwd(Md5Util.md5(userUpdateDto.getPwd()));
        } else {
            user.setPwd(null); // 不更新密码字段
        }
        
        // 设置更新时间
        user.setUpdatedTime(System.currentTimeMillis());
        
        return user;
    }

    /**
     * 验证用户删除条件
     * 
     * @param userId 用户ID
     * @return 验证结果响应
     */
    private R validateUserDeletion(Long userId) {
        User user = this.getById(userId);
        if (user == null) {
            return R.err(ERROR_USER_NOT_FOUND);
        }

        if (user.getRoleId() == ADMIN_ROLE_ID) {
            return R.err(ERROR_CANNOT_DELETE_ADMIN);
        }

        return R.ok();
    }

    /**
     * 验证用户更新条件
     * 
     * @param userId 用户ID
     * @return 验证结果响应
     */
    private R validateUserUpdate(Long userId) {
        User user = this.getById(userId);
        if (user == null) {
            return R.err(ERROR_USER_NOT_FOUND);
        }

        if (user.getRoleId() == ADMIN_ROLE_ID) {
            return R.err(ERROR_CANNOT_UPDATE_ADMIN);
        }

        return R.ok();
    }

    /**
     * 删除用户相关的所有数据
     * 
     * @param userId 用户ID
     */
    private void deleteUserRelatedData(Long userId) {
        // 1. 删除用户的所有转发和对应的Gost服务
        deleteUserForwardsAndGostServices(userId);

        // 2. 删除用户隧道权限
        deleteUserTunnelPermissions(userId);

        // 3. 删除协议/中转的分配记录和线路记录。
        //    不删的话订阅 token、配额、到期这些会一直留在库里变成孤儿数据,
        //    「我的订阅」「用户订阅线路」那些查询也会读到已经不存在的人。
        try {
            inboundUserMapper.delete(new QueryWrapper<InboundUser>().eq("user_id", userId));
            inboundLineMapper.delete(new QueryWrapper<InboundLine>().eq("user_id", userId));
        } catch (Exception e) {
            System.err.println("删除用户的协议分配/线路记录失败，用户ID: " + userId + ", 错误: " + e.getMessage());
        }
    }

    /**
     * 删除用户转发和对应的Gost服务
     * 
     * @param userId 用户ID
     */
    private void deleteUserForwardsAndGostServices(Long userId) {
        QueryWrapper<Forward> forwardQuery = new QueryWrapper<>();
        forwardQuery.eq("user_id", userId);
        List<Forward> userForwards = forwardMapper.selectList(forwardQuery);
        
        for (Forward forward : userForwards) {
            try {
                // 删除Gost服务
                deleteGostServicesForForward(forward, userId);
            } catch (Exception e) {
                // 记录错误但继续删除，避免因为Gost服务删除失败而阻断用户删除
                System.err.println("删除用户转发对应的Gost服务失败，转发ID: " + forward.getId() + ", 错误: " + e.getMessage());
            }
            
            // 删除数据库中的转发记录
            forwardMapper.deleteById(forward.getId());
        }
    }

    /**
     * 删除转发对应的Gost服务
     * 
     * @param forward 转发对象
     * @param userId 用户ID
     */
    private void deleteGostServicesForForward(Forward forward, Long userId) {
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) return;

        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) return;

        // 获取用户隧道关系
        UserTunnel userTunnel = getUserTunnelRelation(userId, tunnel.getId());

        // 先按原名删,原名 not found 才按 _0 逐个确认(GostDeleteFallback)。
        // 协议/线路/分给车友的转发建的时候恒为 _0:车友在那条隧道上另有 user_tunnel 时原名对不上;
        // 没有 user_tunnel 时以前直接 return、一个都不删 —— 两种都会在节点上留下没人管的服务。
        List<String> names = GostDeleteFallback.candidateNames(forward.getId(), userId.intValue(),
                userTunnel != null ? userTunnel.getId() : null);
        Long inNodeId = inNode.getId();

        // 删除主服务
        GostDeleteFallback.delete(names,
                n -> GostUtil.DeleteService(inNodeId, n),
                Arrays.asList(n -> GostUtil.DeleteOneService(inNodeId, n + "_tcp"),
                        n -> GostUtil.DeleteOneService(inNodeId, n + "_udp")));

        // 如果是隧道转发，还需要删除链和远程服务
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            deleteGostTunnelForwardServices(tunnel, names, inNode);
        }
    }

    /**
     * 删除隧道转发相关的Gost服务
     * 
     * @param tunnel 隧道对象
     * @param names 候选服务名(第一个是原名)
     * @param inNode 入口节点
     */
    private void deleteGostTunnelForwardServices(Tunnel tunnel, List<String> names, Node inNode) {
        Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
        if (outNode != null) {
            GostDeleteFallback.delete(names, n -> GostUtil.DeleteChains(inNode.getId(), n), Collections.emptyList());
            GostDeleteFallback.delete(names, n -> GostUtil.DeleteRemoteService(outNode.getId(), n), Collections.emptyList());
        }
    }

    /**
     * 获取用户隧道关系
     * 
     * @param userId 用户ID
     * @param tunnelId 隧道ID
     * @return 用户隧道关系对象
     */
    private UserTunnel getUserTunnelRelation(Long userId, Long tunnelId) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId));
    }

    /**
     * 删除用户隧道权限
     * 
     * @param userId 用户ID
     */
    private void deleteUserTunnelPermissions(Long userId) {
        QueryWrapper<UserTunnel> userTunnelQuery = new QueryWrapper<>();
        userTunnelQuery.eq("user_id", userId);
        userTunnelMapper.delete(userTunnelQuery);
    }

    /**
     * 获取当前用户信息
     * 
     * @return 当前用户信息结果
     */
    private CurrentUserInfo getCurrentUserInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        
        if (userId == null) {
            return CurrentUserInfo.error(ERROR_USER_NOT_LOGGED_IN);
        }
        
        User user = this.getById(userId);
        if (user == null) {
            return CurrentUserInfo.error(ERROR_USER_NOT_FOUND);
        }
        
        return CurrentUserInfo.success(user, roleId);
    }

    /**
     * 构建用户套餐信息DTO
     * 
     * @param currentUser 当前用户信息
     * @return 用户套餐信息DTO
     */
    private UserPackageDto buildUserPackageDto(CurrentUserInfo currentUser) {
        User user = currentUser.getUser();
        Integer roleId = currentUser.getRoleId();
        
        // 1. 构造用户基本信息
        UserPackageDto.UserInfoDto userInfo = buildUserInfoDto(user);
        
        // 2. 获取隧道权限详情
        List<UserPackageDto.UserTunnelDetailDto> tunnelPermissions = getTunnelPermissions(user.getId());
        
        // 3. 获取转发详情
        List<UserPackageDto.UserForwardDetailDto> forwards = userMapper.getUserForwardDetails(user.getId().intValue());

        // 4. 查询最近24小时流量信息，没有的补0
        List<StatisticsFlow> statisticsFlows = getLast24HoursFlowStatistics(user.getId());
        
        // 5. 构造返回结果
        UserPackageDto packageDto = new UserPackageDto();
        packageDto.setUserInfo(userInfo);
        packageDto.setTunnelPermissions(tunnelPermissions);
        packageDto.setForwards(forwards);
        packageDto.setStatisticsFlows(statisticsFlows);
        
        return packageDto;
    }

    /**
     * 构建用户基本信息DTO
     * 
     * @param user 用户对象
     * @return 用户基本信息DTO
     */
    private UserPackageDto.UserInfoDto buildUserInfoDto(User user) {
        UserPackageDto.UserInfoDto userInfo = new UserPackageDto.UserInfoDto();
        userInfo.setId(user.getId());
        userInfo.setUser(user.getUser());
        userInfo.setStatus(user.getStatus());
        userInfo.setFlow(user.getFlow());
        userInfo.setInFlow(user.getInFlow());
        userInfo.setOutFlow(user.getOutFlow());
        userInfo.setNum(user.getNum());
        userInfo.setExpTime(user.getExpTime());
        userInfo.setFlowResetTime(user.getFlowResetTime());
        userInfo.setCreatedTime(user.getCreatedTime());
        userInfo.setUpdatedTime(user.getUpdatedTime());
        return userInfo;
    }

    /**
     * 获取隧道权限详情
     * 
     * @param userId 用户ID
     * @return 隧道权限详情列表
     */
    private List<UserPackageDto.UserTunnelDetailDto> getTunnelPermissions(Long userId) {
        return userMapper.getUserTunnelDetails(userId.intValue());
    }

    /**
     * 获取用户最近24小时的流量统计数据，没有数据的时间点补0
     * 
     * @param userId 用户ID
     * @return 最近24小时流量统计列表
     */
    private List<StatisticsFlow> getLast24HoursFlowStatistics(Long userId) {
        // 按ID倒序查最近24条记录（ID越大越新，时间就是23:00, 22:00, 21:00...这样倒序）
        List<StatisticsFlow> recentFlows = statisticsFlowService.list(
                new QueryWrapper<StatisticsFlow>()
                        .eq("user_id", userId)
                        .orderByDesc("id")
                        .last("LIMIT 24")
        );

        List<StatisticsFlow> result = new ArrayList<>(recentFlows);

        // 如果查出来的记录不足24条，需要补0和对应的时间
        if (result.size() < 24) {
            // 获取最早记录的时间，继续往前推
            int startHour = getCurrentHour();
            if (!result.isEmpty()) {
                // 从最后一条记录的时间继续往前推
                String lastTime = result.get(result.size() - 1).getTime();
                startHour = parseHour(lastTime) - 1;
            }

            // 补0到24条
            while (result.size() < 24) {
                if (startHour < 0) startHour = 23; // 跨天处理

                StatisticsFlow emptyFlow = new StatisticsFlow();
                emptyFlow.setUserId(userId);
                emptyFlow.setFlow(0L);
                emptyFlow.setTotalFlow(0L);
                emptyFlow.setTime(String.format("%02d:00", startHour));
                result.add(emptyFlow);

                startHour--;
            }
        }

        log.info("用户 {} 获取到 {} 条实际记录，补齐为 {} 条24小时记录", userId, recentFlows.size(), result.size());
        return result;

    }

    /**
     * 获取当前小时（0-23）
     */
    private int getCurrentHour() {
        return java.time.LocalDateTime.now().getHour();
    }

    /**
     * 解析时间字符串获取小时数
     */
    private int parseHour(String timeStr) {
        try {
            if (timeStr != null && timeStr.contains(":")) {
                return Integer.parseInt(timeStr.split(":")[0]);
            }
        } catch (Exception e) {
            // 解析失败，返回当前小时
        }
        return getCurrentHour();
    }


    // ========== 内部数据类 ==========

    /**
     * 登录验证结果封装类
     */
    @Data
    private static class LoginValidationResult {
        private final boolean hasError;
        private final String errorMessage;
        private final User user;

        private LoginValidationResult(boolean hasError, String errorMessage, User user) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.user = user;
        }

        public static LoginValidationResult success(User user) {
            return new LoginValidationResult(false, null, user);
        }

        public static LoginValidationResult error(String errorMessage) {
            return new LoginValidationResult(true, errorMessage, null);
        }
    }

    /**
     * 当前用户信息封装类
     */
    @Data
    private static class CurrentUserInfo {
        private final boolean hasError;
        private final String errorMessage;
        private final User user;
        private final Integer roleId;

        private CurrentUserInfo(boolean hasError, String errorMessage, User user, Integer roleId) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.user = user;
            this.roleId = roleId;
        }

        public static CurrentUserInfo success(User user, Integer roleId) {
            return new CurrentUserInfo(false, null, user, roleId);
        }

        public static CurrentUserInfo error(String errorMessage) {
            return new CurrentUserInfo(true, errorMessage, null, null);
        }
    }
}
