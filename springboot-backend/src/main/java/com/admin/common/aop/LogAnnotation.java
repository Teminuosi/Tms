package com.admin.common.aop;


import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogAnnotation {
    /**
     * true = 正常返回时的【请求日志】只打 DEBUG(默认级别 INFO 下不落盘)。
     * 只给节点高频上报接口用(如 /flow/upload),否则日志一个月能涨好几 GB。异常日志不受影响。
     */
    boolean quiet() default false;
}
