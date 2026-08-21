package com.doctorpet.domain.notification.migration;

// Hibernate가 VARCHAR enum 컬럼에 자동 생성하는 CHECK 제약을 찾아 드롭하는 공용 도구(이슈 #176).

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * `@Enumerated(EnumType.STRING)` 컬럼을 VARCHAR로 만들 때 Hibernate가 함께 생성하는 값 열거 CHECK 제약
 * (`check (type in ('NO_SHOW',…))`)을 정리한다.
 *
 * <p>왜 필요한가: 이 제약을 남겨두면 native ENUM을 없앤 의미가 사라진다 — enum에 값을 추가했을 때 기존 DB에서
 * 그 값의 INSERT만 실패하는 증상이 MySQL 1265(ENUM) 대신 3819(CHECK)로 <b>이름만 바뀐 채 그대로 재현</b>된다.
 * `@Column(columnDefinition = …)`으로도 억제되지 않음을 실측했으므로 스키마 쪽에서 드롭한다.
 *
 * <p>이 제약은 <b>새로 생성되는 테이블에만</b> 붙는다 — `ddl-auto=update`는 이미 있는 테이블에 CHECK를 추가하지
 * 않는다(실측). 그래서 ENUM 시절에 만들어진 오래된 DB에는 없고, 빈 DB에서 생성된 테이블(CI·신규 배포)에만 있다.
 *
 * <p>제약 이름은 MySQL이 `<table>_chk_N`으로 자동 부여하고 N이 생성 순서에 따라 달라지므로 하드코딩하지 않고,
 * CHECK 절이 대상 컬럼을 backtick으로 참조하는 것만 골라 드롭한다(backtick 경계 덕분에 `resource_type` 제약이
 * `type` 매칭에 걸리지 않는다). 대상 컬럼 외의 CHECK 제약은 건드리지 않는다.
 */
final class NotificationEnumCheckConstraints {

    private NotificationEnumCheckConstraints() {
    }

    /**
     * 대상 컬럼을 참조하는 CHECK 제약을 모두 드롭한다. 없으면 아무것도 하지 않는다(멱등).
     *
     * @return 드롭한 제약 수
     */
    static int drop(Connection connection, String table, List<String> columns) throws SQLException {
        List<String> names = names(connection, table, columns);
        for (String name : names) {
            try (Statement statement = connection.createStatement()) {
                // MySQL 8.0.16+ 문법. 이름은 information_schema에서 읽은 값이라 외부 입력이 아니다.
                statement.executeUpdate("alter table " + table + " drop check `" + name + "`");
            }
        }
        return names.size();
    }

    /** 대상 컬럼을 참조하는 CHECK 제약 이름 목록. 드롭 대상 판정과 사후 단정이 같은 기준을 쓰게 한다. */
    static List<String> names(Connection connection, String table, List<String> columns) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select tc.constraint_name, cc.check_clause
                  from information_schema.table_constraints tc
                  join information_schema.check_constraints cc
                    on tc.constraint_schema = cc.constraint_schema
                   and tc.constraint_name = cc.constraint_name
                 where tc.table_schema = database()
                   and tc.table_name = ?
                   and tc.constraint_type = 'CHECK'
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String clause = resultSet.getString("check_clause");
                    if (clause != null && referencesAny(clause, columns)) {
                        names.add(resultSet.getString("constraint_name"));
                    }
                }
            }
        }
        return names;
    }

    private static boolean referencesAny(String checkClause, List<String> columns) {
        return columns.stream().anyMatch(column -> checkClause.contains("`" + column + "`"));
    }
}
