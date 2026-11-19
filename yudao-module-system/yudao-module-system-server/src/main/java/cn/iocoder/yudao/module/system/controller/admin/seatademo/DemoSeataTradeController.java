package cn.iocoder.yudao.module.system.controller.admin.seatademo;

import cn.iocoder.yudao.module.system.seatademo.DemoSeataTradeService;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 分布式事务演示的 HTTP 入口
 *
 * 说明：官方的示例材料只提供了 {@link DemoSeataTradeService}（全局事务入口），没有暴露 HTTP 接口，
 * 这里补一个 Controller，方便用 curl 直接触发「跨服务全局事务」，也方便演示「余额不足 → 订单回滚」。
 *
 * @author campus-project
 */
@Tag(name = "管理后台 - 分布式事务演示")
@RestController
@RequestMapping("/system/seata-demo")
public class DemoSeataTradeController {

    @Resource
    private DemoSeataTradeService demoSeataTradeService;

    @PostMapping("/place-order")
    @Operation(summary = "下单：创建订单 + 扣减余额（跨 system / infra 两个服务的全局事务）")
    @Parameter(name = "orderId", description = "订单编号（调用方生成，作为幂等主键）", required = true)
    @Parameter(name = "userId", description = "用户编号", required = true)
    @Parameter(name = "amount", description = "订单金额（分）", required = true)
    public CommonResult<Boolean> placeOrder(@RequestParam("orderId") Long orderId,
                                            @RequestParam("userId") Long userId,
                                            @RequestParam("amount") Integer amount) {
        demoSeataTradeService.placeOrder(orderId, userId, amount);
        return success(true);
    }

}
