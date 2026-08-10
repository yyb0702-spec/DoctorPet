-- DoctorPet 로컬 개발용 샘플 병원 (공공데이터 점검 기간 대체). 재실행 가능(멱등).
-- 실연동 검색/상세 테스트용. id 1~5 고정.
SET NAMES utf8mb4;

-- 기존 샘플 정리 (자식 → 부모 순)
DELETE FROM reservation_slots WHERE hospital_id BETWEEN 1 AND 10;
DELETE FROM hospital_capabilities WHERE hospital_id BETWEEN 1 AND 10;
DELETE FROM hospital_details WHERE hospital_id BETWEEN 1 AND 10;
DELETE FROM hospitals WHERE id BETWEEN 1 AND 10;

-- 병원 (다양한 케이스)
INSERT INTO hospitals
  (id, name, business_status, partnership_status, local_gov_code, mgmt_no,
   phone, address_road, address_jibun, zipcode, coord_x, coord_y,
   license_date, source_modified_at)
VALUES
  (1, '행복동물메디컬센터', 'OPEN', 'PARTNER', '3130000', 'SAMPLE-0001',
   '02-1234-5678', '서울 강남구 테헤란로 123', '서울 강남구 역삼동 100', '06134',
   127.03, 37.50, '2019-03-01', NOW()),
  (2, '24시 서울동물메디컬센터', 'OPEN', 'PARTNER', '3130000', 'SAMPLE-0002',
   '02-9876-5432', '서울 송파구 올림픽로 200', '서울 송파구 잠실동 200', '05551',
   127.11, 37.51, '2015-06-15', NOW()),
  (3, '냥이사랑 고양이병원', 'OPEN', 'PARTNER', '3130000', 'SAMPLE-0003',
   '02-3333-4444', '서울 마포구 월드컵로 50', '서울 마포구 성산동 50', '03925',
   126.90, 37.55, '2021-09-10', NOW()),
  (4, '튼튼 동물의원', 'OPEN', 'NON_PARTNER', '3130000', 'SAMPLE-0004',
   '031-777-8888', '경기 성남시 분당구 판교로 55', '경기 성남시 분당구 삼평동 55', '13494',
   127.10, 37.39, '2018-01-20', NOW()),
  (5, '밤비 동물병원', 'CLOSED_TEMP', 'PARTNER', '3130000', 'SAMPLE-0005',
   '02-5555-6666', '서울 강북구 도봉로 10', '서울 강북구 미아동 10', '01188',
   127.02, 37.64, '2017-11-05', NOW());

-- 제휴 병원 상세 (비제휴 id4는 제외)
INSERT INTO hospital_details
  (hospital_id, surgery_available, hospitalization_available, night_care, emergency, open_hours)
VALUES
  (1, 1, 1, 0, 0,
   '{"MONDAY":{"openTime":"09:00","closeTime":"20:00"},"TUESDAY":{"openTime":"09:00","closeTime":"20:00"},"WEDNESDAY":{"openTime":"09:00","closeTime":"20:00"},"THURSDAY":{"openTime":"09:00","closeTime":"20:00"},"FRIDAY":{"openTime":"09:00","closeTime":"20:00"},"SATURDAY":{"openTime":"09:00","closeTime":"18:00"},"SUNDAY":{"openTime":"10:00","closeTime":"17:00"}}'),
  (2, 1, 1, 1, 1,
   '{"MONDAY":{"openTime":"00:00","closeTime":"23:59"},"TUESDAY":{"openTime":"00:00","closeTime":"23:59"},"WEDNESDAY":{"openTime":"00:00","closeTime":"23:59"},"THURSDAY":{"openTime":"00:00","closeTime":"23:59"},"FRIDAY":{"openTime":"00:00","closeTime":"23:59"},"SATURDAY":{"openTime":"00:00","closeTime":"23:59"},"SUNDAY":{"openTime":"00:00","closeTime":"23:59"}}'),
  (3, 0, 0, 0, 0,
   '{"MONDAY":{"openTime":"10:00","closeTime":"19:00"},"TUESDAY":{"openTime":"10:00","closeTime":"19:00"},"WEDNESDAY":{"openTime":"10:00","closeTime":"19:00"},"THURSDAY":{"openTime":"10:00","closeTime":"19:00"},"FRIDAY":{"openTime":"10:00","closeTime":"19:00"},"SATURDAY":{"openTime":"10:00","closeTime":"15:00"}}'),
  (5, 1, 1, 0, 0,
   '{"MONDAY":{"openTime":"09:00","closeTime":"18:00"},"TUESDAY":{"openTime":"09:00","closeTime":"18:00"},"WEDNESDAY":{"openTime":"09:00","closeTime":"18:00"},"THURSDAY":{"openTime":"09:00","closeTime":"18:00"},"FRIDAY":{"openTime":"09:00","closeTime":"18:00"}}');

-- 진료역량
INSERT INTO hospital_capabilities (hospital_id, capability_type, capability_value) VALUES
  (1, 'SPECIES', 'DOG'), (1, 'SPECIES', 'CAT'), (1, 'EXAM', 'XRAY'),
  (1, 'EXAM', 'ULTRASOUND'), (1, 'TREATMENT', 'DENTAL_CARE'),
  (2, 'SPECIES', 'DOG'), (2, 'SPECIES', 'CAT'), (2, 'EXAM', 'BLOOD_TEST'),
  (2, 'EQUIPMENT', 'CT'), (2, 'EQUIPMENT', 'MRI'), (2, 'TREATMENT', 'ONCOLOGY_CARE'),
  (3, 'SPECIES', 'CAT'), (3, 'EXAM', 'XRAY'), (3, 'TREATMENT', 'OPHTHALMIC_CARE'),
  (5, 'SPECIES', 'DOG'), (5, 'EXAM', 'XRAY'), (5, 'TREATMENT', 'ORTHOPEDIC_CARE');

-- 예약 슬롯 (제휴·영업중 id1~3, 내일/모레 오전·오후). 슬롯 조회 API 나오면 바로 사용 가능.
INSERT INTO reservation_slots (hospital_id, start_at, end_at, status, version)
SELECT h, ts, ts + INTERVAL 30 MINUTE, 'OPEN', 0
FROM (
  SELECT 1 h UNION ALL SELECT 2 UNION ALL SELECT 3
) hs
JOIN (
  SELECT TIMESTAMP(CURDATE() + INTERVAL 1 DAY, '10:00:00') ts
  UNION ALL SELECT TIMESTAMP(CURDATE() + INTERVAL 1 DAY, '11:00:00')
  UNION ALL SELECT TIMESTAMP(CURDATE() + INTERVAL 1 DAY, '14:00:00')
  UNION ALL SELECT TIMESTAMP(CURDATE() + INTERVAL 2 DAY, '10:00:00')
) t;
