package com.example.coupon.issue;

import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponRepository;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
@Profile("redis")
public class RedisCouponIssuer implements CouponIssuer {

	private static final Long ISSUED = 1L;

	private final CouponRepository couponRepository;
	private final CouponIssueRecorder recorder;
	private final StringRedisTemplate redisTemplate;
	private final RedisScript<Long> issueScript;

	public RedisCouponIssuer(CouponRepository couponRepository, CouponIssueRecorder recorder,
			StringRedisTemplate redisTemplate) {
		this.couponRepository = couponRepository;
		this.recorder = recorder;
		this.redisTemplate = redisTemplate;
		this.issueScript = RedisScript.of(new ClassPathResource("redis/coupon-issue.lua"), Long.class);
	}

	// @Transactional을 붙이지 않는다. 트랜잭션 안에서 Redis를 부르면 Redis가 느려지는 동안
	// DB 커넥션이 붙잡힌다. 풀이 10개인 환경에서는 치명적이다.
	//
	// 한도를 매번 DB에서 읽는 것은 Phase 1, 2와 조건을 맞추기 위해서다. 달라지는 것이
	// count()에서 Redis 카운터 하나뿐이어야 세 방식의 비교가 성립한다. 실서비스라면
	// 쿠폰 메타를 캐시해 이 왕복을 없앨 수 있으므로, 여기서 재는 값은 이 방식의 하한이다.
	@Override
	public boolean issue(Long couponId, Long userId) {
		Coupon coupon = couponRepository.findById(couponId).orElseThrow();

		Long result = redisTemplate.execute(issueScript,
				List.of(issuedKey(couponId), usersKey(couponId)),
				String.valueOf(userId), String.valueOf(coupon.getTotalQuantity()));

		if (!ISSUED.equals(result)) {
			return false;
		}

		recorder.record(couponId, userId);
		return true;
	}

	// Redis Cluster는 스크립트가 만지는 키가 같은 슬롯에 있을 것을 요구한다. 중괄호 안만
	// 해싱하므로 두 키가 항상 같은 노드에 놓인다. 단일 노드에서는 효과가 없지만 키 이름은
	// 운영 중에 바꾸기 어려워 지금 넣어둔다.
	private String issuedKey(Long couponId) {
		return "{coupon:" + couponId + "}:issued";
	}

	private String usersKey(Long couponId) {
		return "{coupon:" + couponId + "}:users";
	}
}
