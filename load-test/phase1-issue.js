import http from 'k6/http';
import { Counter } from 'k6/metrics';

const issued = new Counter('coupon_issued');
const soldOut = new Counter('coupon_sold_out');
const failed = new Counter('coupon_failed');

export const options = {
	vus: 200,
	duration: '30s',
	// k6 기본 요약은 p(90)과 p(95)만 낸다. 이 프로젝트는 p99를 기록해야 한다.
	summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const couponId = __ENV.COUPON_ID;
const url = `http://localhost:8080/coupons/${couponId}/issues`;

export default function () {
	// VU 번호와 반복 횟수를 곱해 흩어놓는다. 문자열 연결로 만들면
	// VU 1/반복 12와 VU 11/반복 2가 같은 값이 된다.
	const userId = __VU * 1000000 + __ITER;

	const res = http.post(url, JSON.stringify({ userId }), {
		headers: { 'Content-Type': 'application/json' },
	});

	if (res.status === 200) {
		issued.add(1);
	} else if (res.status === 409) {
		soldOut.add(1);
	} else {
		failed.add(1);
	}
}
