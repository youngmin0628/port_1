alter table coupon_issue
    add constraint uk_coupon_issue_coupon_user unique (coupon_id, user_id);
