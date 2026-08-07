package com.doctorpet.domain.hospital.migration;

record IndexDefinition(
        String table,
        String name,
        String columns,
        String createSql
) {
}
