package com.admin.entity;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class Forward extends BaseEntity{

    private static final long serialVersionUID = 1L;

    private Integer userId;

    private String userName;

    private String name;

    private Integer tunnelId;

    private Integer inPort;

    private Integer outPort;

    private String remoteAddr;

    private String interfaceName;

    private String strategy;

    private Long inFlow;

    private Long outFlow;

    private Integer inx;

    /** 单条转发绑定的限速规则ID(功能B),为空则用用户隧道默认规则 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Integer speedId;

    /** 单条转发到期时间(epoch ms,功能C),为空=永不过期 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long expTime;

    /** 给车友的客户端链接:分配转发时由客户端算好推上来,聚合订阅里原样吐出。仅分配给车友的转发有值 */
    private String clientLink;

}
