package com.justpickup.orderservice.domain.order.service;

import com.justpickup.orderservice.domain.order.dto.*;
import com.justpickup.orderservice.domain.order.entity.Order;
import com.justpickup.orderservice.domain.order.entity.OrderStatus;
import com.justpickup.orderservice.domain.order.exception.OrderException;
import com.justpickup.orderservice.domain.order.repository.OrderRepository;
import com.justpickup.orderservice.domain.order.repository.OrderRepositoryCustom;
import com.justpickup.orderservice.domain.orderItem.dto.OrderItemDto;
import com.justpickup.orderservice.domain.orderItem.entity.OrderItem;
import com.justpickup.orderservice.domain.orderItemOption.entity.OrderItemOption;
import com.justpickup.orderservice.global.client.store.GetItemsResponse;
import com.justpickup.orderservice.global.client.store.StoreByUserIdResponse;
import com.justpickup.orderservice.global.client.store.StoreClient;
import com.justpickup.orderservice.global.client.user.GetCustomerResponse;
import com.justpickup.orderservice.global.client.user.UserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderRepositoryCustom orderRepositoryCustom;
    private final OrderSender orderSender;
    private final StoreClient storeClient;
    private final UserClient userClient;

    @Override
    public OrderMainDto findOrderMain(OrderSearchCondition condition, Long userId) {
        // storeId 가져오기
        StoreByUserIdResponse storeResponse = storeClient.getStoreByUserId(userId).getData();

        // 주문 가져오기
        OrderMainResult orderMainResult = orderRepositoryCustom.findOrderMain(condition, storeResponse.getId());

        // 사용자 고유번호 및 아이템 고유번호 필터링
        Set<Long> userIds = new HashSet<>();
        Set<Long> itemIds = new HashSet<>();

        OrderMainDto returnDto = OrderMainDto.of(orderMainResult.getOrders(), orderMainResult.isHasNext());
        List<OrderMainDto._Order> orders = returnDto.getOrders();

        // userId 및 itemId Set에 추가
        for (OrderMainDto._Order order : orders) {
            userIds.add(order.getUserId());
            for (OrderMainDto._OrderItem orderItem : order.getOrderItems()) {
                itemIds.add(orderItem.getItemId());
            }
        }

        // item name 가져오기
        Map<Long, String> itemNameMap = getItemNameMap(itemIds);

        // user name 가져오기
        Map<Long, String> userNameMap = getUserNameMap(userIds);

        // 해당 ID에 맞게 이름 설정해주기
        for (OrderMainDto._Order order : orders) {
            String userName = userNameMap.get(order.getUserId());
            order.changeUserName(userName);
            for (OrderMainDto._OrderItem orderItem : order.getOrderItems()) {
                String itemName = itemNameMap.get(orderItem.getItemId());
                orderItem.changeItemName(itemName);
            }
        }

        return returnDto;
    }

    private Map<Long, String> getUserNameMap(Iterable<Long> userIds) {
        List<GetCustomerResponse> userResponses = userClient.getCustomers(userIds).getData();
        return userResponses.stream()
                .collect(
                        toMap(GetCustomerResponse::getUserId, GetCustomerResponse::getUserName)
                );
    }

    private Map<Long, String> getItemNameMap(Iterable<Long> itemIds) {
        List<GetItemsResponse> itemResponses = storeClient.getItems(itemIds).getData();
        return itemResponses.stream()
                .collect(
                        toMap(GetItemsResponse::getId, GetItemsResponse::getName)
                );
    }

    @Override
    public Page<PrevOrderDto> findPrevOrderMain(PrevOrderSearch search, Pageable pageable, Long userId) {
        StoreByUserIdResponse store = storeClient.getStoreByUserId(userId).getData();

        Page<Order> orderPage = orderRepositoryCustom.findPrevOrderMain(search, pageable, store.getId());

        List<PrevOrderDto> prevOrderDtoList = orderPage.getContent()
                .stream()
                .map(PrevOrderDto::of)
                .collect(toList());

        // 사용자명 및 아이템 이름 가져오기
        Set<Long> userIds = new HashSet<>();
        Set<Long> itemIds = new HashSet<>();

        for (PrevOrderDto prevOrderDto : prevOrderDtoList) {
            userIds.add(prevOrderDto.getUserId());
            for (PrevOrderDto._PrevOrderItem orderItem : prevOrderDto.getOrderItems()) {
                itemIds.add(orderItem.getItemId());
            }
        }

        // item name 가져오기
        Map<Long, String> itemNameMap = getItemNameMap(itemIds);

        // user name 가져오기
        Map<Long, String> userNameMap = getUserNameMap(userIds);

        for (PrevOrderDto prevOrderDto : prevOrderDtoList) {
            String userName = userNameMap.get(prevOrderDto.getUserId());
            prevOrderDto.changeUserName(userName);
            for (PrevOrderDto._PrevOrderItem orderItem : prevOrderDto.getOrderItems()) {
                String itemName = itemNameMap.get(orderItem.getItemId());
                orderItem.changeName(itemName);
            }
        }

        return PageableExecutionUtils.getPage(prevOrderDtoList, pageable, orderPage::getTotalElements);
    }

    @Override
    public SliceImpl<OrderDto> findOrderHistory(Pageable pageable, Long userId) {
        SliceImpl<Order> orderHistory = orderRepositoryCustom.findOrderHistory(pageable, userId);

        List<OrderDto> contents = orderHistory.getContent()
                .stream()
                .map(OrderDto::createFullField)
                .collect(toList());

        // TODO: 2022/03/07 Feign Client 통신

        return new SliceImpl<>(contents, pageable, orderHistory.hasNext());
    }

    @Override
    @Transactional
    public void addItemToBasket(OrderItemDto orderItemDto,Long storeId, Long userId) {

        //orderItemOption Entity를 생성한다.
        List<OrderItemOption> orderItemOptions = orderItemDto.getOrderItemOptionDtoList()
                .stream().map(orderItemOptionDto -> OrderItemOption.of(orderItemDto.getId()))
                .collect(toList());

        //orderItem을 Entity를 생성한다.
        OrderItem orderItem = OrderItem.of(orderItemDto.getItemId()
                , orderItemDto.getPrice()
                , orderItemDto.getCount()
                ,orderItemOptions);

        //HARD_CODE
        Long userCouponId=0L;

        Optional<Order> optionalOrder = orderRepository.findByUserIdAndOrderStatus(userId, OrderStatus.PENDING);
        if(optionalOrder.isPresent()){
            if(optionalOrder.get().addOrderItem(orderItem)
                    .getStoreId().equals(storeId))
                throw new OrderException("장바구니에 여러 카페의 메뉴를 담을수 없습니다.");
        }else{
            orderRepository.save(Order.of(userId,userCouponId,storeId,orderItem));
        }
    }

    @Override
    public FetchOrderDto fetchOrder(Long userId) {
        Order order = orderRepositoryCustom.fetchOrder(userId)
                .orElseThrow(() -> new OrderException("장바구니 정보를 찾을 수 없습니다."));

        return new FetchOrderDto(order);
    }

    @Override
    @Transactional
    public void saveOrder(Long userId) {
        Order order = orderRepository.findByUserIdAndOrderStatus(userId, OrderStatus.PENDING)
                .orElseThrow(() -> new OrderException("장바구니 정보를 찾을 수 없습니다."))
                .setOrderStatus(OrderStatus.PLACED);
        try{
            orderSender.orderPlaced(OrderSender.KafkaSendOrderDto.createPrimitiveField(order));
        }catch (Exception ex){
            throw new OrderException(ex.getMessage());
        }
    }

    @Override
    @Transactional
    public void modifyOrder(Long orderId, OrderStatus orderStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderException(orderId + "는 없는 주문 번호입니다."));

        order.setOrderStatus(orderStatus);
    }

}
