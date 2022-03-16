package com.justpickup.orderservice.domain.order.entity;

import com.justpickup.orderservice.domain.order.exception.OrderException;
import com.justpickup.orderservice.domain.order.service.OrderSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import javax.persistence.*;

@Slf4j
public class OrderListener {

    @Autowired
    @Lazy
    private OrderSender orderSender;


    // TODO: 2022/03/15 exception 발생시 order fail 처리
    @PostUpdate
    public void postUpdate(Order order){
        OrderStatus orderStatus = order.getOrderStatus();
        if (orderStatus == OrderStatus.ORDER) {
            log.info("[OrderListener] {}", OrderStatus.ORDER.name());
            try{
                orderSender.orderPlaced(OrderSender.KafkaSendOrderDto.createPrimitiveField(order));
            }catch (Exception ex){
                throw new OrderException(ex.getMessage());
            }

        } else if (orderStatus == OrderStatus.PLACED) {
            log.info("[OrderListener] {}", OrderStatus.PLACED.name());
        }
    }
}