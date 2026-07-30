alter table coupon
    add column issued_quantity int    not null default 0,
    add column version         bigint not null default 0;
