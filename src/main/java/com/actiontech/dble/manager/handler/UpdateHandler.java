/*
 * Copyright (C) 2016-2020 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.manager.handler;

import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.manager.ManagerConnection;
import com.actiontech.dble.manager.information.ManagerBaseTable;
import com.actiontech.dble.manager.information.ManagerSchemaInfo;
import com.actiontech.dble.manager.information.ManagerTableUtil;
import com.actiontech.dble.manager.information.ManagerWritableTable;
import com.actiontech.dble.net.mysql.OkPacket;
import com.actiontech.dble.net.mysql.RowDataPacket;
import com.actiontech.dble.route.factory.RouteStrategyFactory;
import com.actiontech.dble.route.parser.druid.ServerSchemaStatVisitor;
import com.actiontech.dble.server.util.SchemaUtil;
import com.actiontech.dble.util.StringUtil;
import com.alibaba.druid.sql.ast.expr.SQLNullExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;

public final class UpdateHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateHandler.class);

    public void handle(String stmt, ManagerConnection c) {
        MySqlUpdateStatement update;
        try {
            update = (MySqlUpdateStatement) (RouteStrategyFactory.getRouteStrategy().parserSQL(stmt));
        } catch (Exception e) {
            LOGGER.warn("manager parser insert failed", e);
            c.writeErrMessage("42000", "You have an error in your SQL syntax", ErrorCode.ER_PARSE_ERROR);
            return;
        }
        if (update.getLimit() != null || update.isIgnore() || update.isLowPriority() || update.getOrderBy() != null) {
            c.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "update syntax error, not support update with syntax :[LOW_PRIORITY] [IGNORE] ... [ORDER BY ...] [LIMIT row_count]");
            return;
        }
        if (update.getWhere() == null) {
            c.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "update syntax error, not support update without WHERE");
            return;
        }
        SQLTableSource tableSource = update.getTableSource();
        if (tableSource instanceof SQLJoinTableSource) {
            c.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "update syntax error, not support update Multiple-Table ");
            return;
        }
        SQLExprTableSource singleTableSource = (SQLExprTableSource) tableSource;
        if (singleTableSource.getAlias() != null) {
            c.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "update syntax error, not support update with alias");
            return;
        }
        if (singleTableSource.getPartitionSize() != 0) {
            c.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "update syntax error, not support update with [PARTITION (partition_name [, partition_name] ...)]");
            return;
        }
        ServerSchemaStatVisitor visitor = new ServerSchemaStatVisitor();
        update.accept(visitor);
        if (visitor.getNotSupportMsg() != null) {
            c.writeErrMessage(ErrorCode.ER_PARSE_ERROR, visitor.getNotSupportMsg());
            return;
        } else if (visitor.getFirstClassSubQueryList().size() > 0) {
            c.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "update syntax error, not support sub-query");
            return;
        }
        SchemaUtil.SchemaInfo schemaInfo;
        try {
            schemaInfo = SchemaUtil.getSchemaInfo(c.getUser(), c.getSchema(), singleTableSource);
        } catch (SQLException e) {
            c.writeErrMessage(e.getSQLState(), e.getMessage(), e.getErrorCode());
            return;
        }
        ManagerBaseTable managerBaseTable = ManagerSchemaInfo.getInstance().getTables().get(schemaInfo.getTable());
        if (!managerBaseTable.isWritable()) {
            c.writeErrMessage("42000", "Access denied for table '" + managerBaseTable.getTableName() + "'", ErrorCode.ER_ACCESS_DENIED_ERROR);
            return;
        }
        ManagerWritableTable managerTable = (ManagerWritableTable) managerBaseTable;

        Map<String, String> values;
        try {
            values = getUpdateValues(schemaInfo, managerTable, update.getItems());
        } catch (SQLException e) {
            c.writeErrMessage(e.getSQLState(), e.getMessage(), e.getErrorCode());
            return;
        }

        int rowSize;
        managerTable.getLock().lock();
        try {
            List<RowDataPacket> foundRows = ManagerTableUtil.getFoundRows(c, managerTable, update.getWhere());
            Set<LinkedHashMap<String, String>> affectPks = ManagerTableUtil.getAffectPks(c, managerTable, foundRows);
            rowSize = managerTable.updateRows(affectPks, values);
        } catch (SQLException e) {
            c.writeErrMessage(e.getSQLState(), e.getMessage(), e.getErrorCode());
            return;
        } catch (Exception e) {
            c.writeErrMessage(ErrorCode.ER_YES, "unknown error:" + e.getMessage());
            return;
        } finally {
            managerTable.getLock().unlock();
        }
        OkPacket ok = new OkPacket();
        ok.setPacketId(1);
        ok.setAffectedRows(rowSize);
        ok.write(c);
    }

    private Map<String, String> getUpdateValues(SchemaUtil.SchemaInfo schemaInfo,
                                                ManagerWritableTable managerTable, List<SQLUpdateSetItem> updateItems) throws SQLException {
        Map<String, String> values = new HashMap<>(updateItems.size());
        for (SQLUpdateSetItem item : updateItems) {
            String columnName = getColumnName(item.getColumn().toString().toLowerCase(), schemaInfo.getTable());
            if (managerTable.getColumnType(columnName) == null) {
                throw new SQLException("Unknown column '" + columnName + "' in 'field list'", "42S22", ErrorCode.ER_BAD_FIELD_ERROR);
            }
            if (managerTable.getPrimaryKeyColumns().contains(columnName)) {
                throw new SQLException("Primary column '" + columnName + "' can not be update, please use delete & insert", "42S22", ErrorCode.ER_BAD_FIELD_ERROR);
            }
            if (item.getValue() instanceof SQLNullExpr && managerTable.getNotNullColumns().contains(columnName)) {
                throw new SQLException("Column '" + columnName + "' cannot be null ", "23000", ErrorCode.ER_BAD_NULL_ERROR);
            }
            values.put(columnName, ManagerTableUtil.valueToString(item.getValue()));
        }
        return values;
    }

    private String getColumnName(String columnName, String expectTableName) throws SQLException {
        if (columnName.contains(StringUtil.TABLE_COLUMN_SEPARATOR)) {
            String[] columnInfo = columnName.split("\\.");
            if (columnInfo.length > 3) {
                throw new SQLException("Unknown column '" + columnName + "' in 'field list'", "42S22", ErrorCode.ER_BAD_FIELD_ERROR);
            } else if (columnInfo.length == 3) {
                if (!StringUtil.removeBackQuote(columnInfo[0]).equals(ManagerSchemaInfo.SCHEMA_NAME)) {
                    throw new SQLException("Unknown column '" + columnName + "' in 'field list'", "42S22", ErrorCode.ER_BAD_FIELD_ERROR);
                }
                if (!StringUtil.removeBackQuote(columnInfo[1]).equals(expectTableName)) {
                    throw new SQLException("Unknown column '" + columnName + "' in 'field list'", "42S22", ErrorCode.ER_BAD_FIELD_ERROR);
                }
                columnName = StringUtil.removeBackQuote(columnInfo[2]);
            } else {
                if (!StringUtil.removeBackQuote(columnInfo[0]).equals(expectTableName)) {
                    throw new SQLException("Unknown column '" + columnName + "' in 'field list'", "42S22", ErrorCode.ER_BAD_FIELD_ERROR);
                }
                columnName = StringUtil.removeBackQuote(columnInfo[1]);
            }
        }
        columnName = StringUtil.removeBackQuote(columnName);
        return columnName;
    }
}
