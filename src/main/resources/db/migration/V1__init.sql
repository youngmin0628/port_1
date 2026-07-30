create table coupon (
    id             bigint       not null auto_increment,
    name           varchar(100) not null,
    total_quantity int          not null,
    primary key (id)
) engine = innodb;

create table coupon_issue (
    id        bigint      not null auto_increment,
    coupon_id bigint      not null,
    user_id   bigint      not null,
    issued_at datetime(6) not null,
    primary key (id),
    -- Phase 1의 countByCouponId()가 풀스캔이 되면 측정 대상이 동시성이 아니라 느린 쿼리가 된다.
    key idx_coupon_issue_coupon_id (coupon_id)
) engine = innodb;
