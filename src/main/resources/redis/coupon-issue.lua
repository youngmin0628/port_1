-- KEYS[1] = 발급 수 카운터, KEYS[2] = 발급받은 유저 Set
-- ARGV[1] = userId, ARGV[2] = totalQuantity
-- 반환: 1 발급 성공, 0 품절, -1 이미 발급받은 유저
--
-- Redis는 스크립트를 단일 원자 단위로 실행한다. 중복 검사와 재고 차감 사이에
-- 다른 명령이 끼어들 수 없으므로 Phase 1에서 재현한 read-modify-write 문제가 없다.
-- 아래 INCR 후 DECR은 스크립트 밖에서 하면 위험한 방식이지만, 여기서는 그 중간값을
-- 관찰할 수 있는 주체가 없어 안전하다.

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

local issued = redis.call('INCR', KEYS[1])
if issued > tonumber(ARGV[2]) then
    redis.call('DECR', KEYS[1])
    return 0
end

redis.call('SADD', KEYS[2], ARGV[1])
return 1
