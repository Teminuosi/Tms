package com.admin.controller;


import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/user")
public class UserController extends BaseController {

    @LogAnnotation
    @PostMapping("/login")
    public R login(@Validated @RequestBody LoginDto loginDto) {
        return userService.login(loginDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody UserDto userDto) {
        return userService.createUser(userDto);
    }


    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R readAll() {
        return userService.getAllUsers();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody UserUpdateDto userUpdateDto) {
        return userService.updateUser(userUpdateDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return userService.deleteUser(id);
    }

    @LogAnnotation
    @PostMapping("/package")
    public R getUserPackageInfo() {
        return userService.getUserPackageInfo();
    }

    @LogAnnotation
    @PostMapping("/updatePassword")
    public R updatePassword(@Validated @RequestBody ChangePasswordDto changePasswordDto) {
        return userService.updatePassword(changePasswordDto);
    }

    /**
     * 车友「在用」状态:{users:{userId: 最后有流量的毫秒}, forwards:{forwardId: 同}, now: 面板当前毫秒}。
     * 只在内存里记(面板重启后从头记)。客户端每 10~15 秒拉一次,所以请求日志只打 DEBUG。
     */
    @LogAnnotation(quiet = true)
    @RequireRole
    @PostMapping("/online-map")
    public R onlineMap() {
        return R.ok(com.admin.common.utils.OnlineTracker.snapshot(System.currentTimeMillis()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/reset")
    public R reset(@Validated @RequestBody ResetFlowDto resetFlowDto) {
        return userService.reset(resetFlowDto);
    }



}
