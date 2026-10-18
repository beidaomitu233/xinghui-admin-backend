package com.xinghuiTec.ai.query;

import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.*;
import net.sf.jsqlparser.expression.operators.conditional.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static com.xinghuiTec.ai.contract.AiSceneQueryRuntime.QueryIssue.*;

/** Explicit AST renderer. No original SQL or unknown AST node is sent to JDBC. */
@Component
public final class SqlAstCompiler {
    private static final ExecutorService PARSER = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), r -> { var t = new Thread(r, "ai-sql-parser"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "LOWER", "UPPER", "LENGTH", "CHAR_LENGTH", "COALESCE");
    private static final Set<String> TRUSTED = Set.of("current_user_id", "current_tenant_id", "current_record_id");

    record TrustedValue(String name, String datasetKey) {}
    record Source(String alias, SqlDataset dataset) {}
    record Plan(String normalizedSql, String executionSql, List<Object> parameters, List<QueryColumn> columns,
                Map<String, List<QueryColumn>> dependencies, List<SqlDataset> datasets,
                int maxRows, String currentRecordDatasetKey) {
        Plan {
            parameters = Collections.unmodifiableList(new ArrayList<>(parameters)); columns = List.copyOf(columns);
            dependencies = Map.copyOf(dependencies); datasets = List.copyOf(datasets);
        }
        @Override public String toString() { return "SqlPlan[REDACTED]"; }
    }

    Plan compile(SqlQuery query, List<SqlDataset> allowed) {
        try {
            if (query.sql().codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
                fail(INVALID_SQL);
            var statements = CCJSqlParserUtil.parseStatements(query.sql(), PARSER, parser -> parser.withTimeOut(1000));
            if (statements.size() != 1 || !(statements.get(0) instanceof PlainSelect select)) fail(UNSUPPORTED_SYNTAX);
            var select = (PlainSelect) statements.get(0);
            reject(select, "getWithItemsList", "getIntoTables", "getIntoTempTable", "getTop", "getSkip", "getFirst",
                    "getOracleHierarchical", "getOracleHint", "getPreferringClause", "getQualify", "getLateralViews",
                    "getWindowDefinitions", "getKsqlWindow", "getForXmlPath", "getForMode", "getForClause",
                    "getForUpdateTable", "getWait", "isSkipLocked", "isNoWait", "getIsolation", "getOptimizeFor",
                    "isEmitChanges", "isUsingFinal", "isUsingOnly", "isUseWithNoLog", "getMySqlHintStraightJoin",
                    "getMySqlSqlCalcFoundRows", "getMySqlSqlCacheFlag", "getBigQuerySelectQualifier", "getLimitBy",
                    "getOffset", "getFetch", "getPivot", "getUnPivot", "isOracleSiblings");
            if (select.getDistinct() != null && select.getDistinct().getOnSelectItems() != null) fail(UNSUPPORTED_SYNTAX);
            var sources = new LinkedHashMap<String, Source>();
            register(select.getFromItem(), allowed, sources);
            var joins = select.getJoins() == null ? List.<Join>of() : select.getJoins();
            if (joins.size() > 3) fail(UNSUPPORTED_SYNTAX);
            for (var join : joins) {
                if (join.isSimple() || join.isRight() || join.isFull() || join.isNatural() || join.isCross()
                        || join.isStraight() || join.isApply() || join.isSemi() || join.isGlobal()
                        || join.isWindowJoin() || join.getJoinHint() != null || join.getJoinWindow() != null
                        || (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty())
                        || join.getOnExpressions() == null || join.getOnExpressions().isEmpty()) fail(UNSUPPORTED_SYNTAX);
                register(join.getRightItem(), allowed, sources);
            }
            int maximum = query.maxRows();
            if (select.getLimit() != null) {
                var limit = select.getLimit();
                if (limit.isLimitAll() || limit.isLimitNull() || limit.getOffset() != null || limit.getByExpressions() != null && !limit.getByExpressions().isEmpty()
                        || !(limit.getRowCount() instanceof LongValue)) fail(UNSUPPORTED_SYNTAX);
                long value = ((LongValue) limit.getRowCount()).getValue();
                if (value < 1 || value > 200) fail(UNSUPPORTED_SYNTAX);
                maximum = Math.min(maximum, (int) value);
            }
            var readable = new Emitter(query, sources, false, maximum);
            String normalized = readable.select(select, joins);
            var executable = new Emitter(query, sources, true, maximum);
            String sql = executable.select(select, joins);
            // Cap only final returned strings. Capping source fields would silently change filters, DISTINCT and aggregation.
            var output = new ArrayList<String>();
            for(var column:readable.columns) {
                String reference="`sql_result`."+quoted(column.name());
                output.add((column.type().equals("STRING")||column.type().equals("JSON")?"LEFT("+reference+",8193)":reference)+" AS "+quoted(column.name()));
            }
            sql="SELECT /*+ MAX_EXECUTION_TIME(10000) */ "+String.join(", ",output)+" FROM ("+sql+") AS `sql_result`";
            var dependencies = new LinkedHashMap<String, List<QueryColumn>>();
            for (var source : sources.values()) {
                var names = executable.dependencies.getOrDefault(source.dataset().schema().datasetKey(), new TreeSet<>());
                dependencies.put(source.dataset().schema().datasetKey(), names.stream().map(source.dataset()::field).toList());
            }
            return new Plan(normalized, sql, executable.parameters, readable.columns, dependencies,
                    sources.values().stream().map(Source::dataset).distinct().toList(), maximum, readable.currentDataset);
        } catch (SqlQueryException safe) { throw safe; }
        catch (Exception invalid) { throw new SqlQueryException(INVALID_SQL); }
    }

    private void register(FromItem item, List<SqlDataset> allowed, LinkedHashMap<String, Source> sources) {
        if (!(item instanceof Table table)) fail(UNSUPPORTED_SYNTAX);
        var table = (Table) item;
        reject(table, "getSchemaName", "getDatabaseName", "getCatalogName", "getPivot", "getUnPivot",
                "getIndexHint", "getSqlServerHints", "getSampleClause");
        if (table.getNameParts().size() != 1) fail(DATASET_FORBIDDEN);
        String name = identifier(table.getName());
        var matches = allowed.stream().filter(d -> d.schema().sqlName().equalsIgnoreCase(name)).toList();
        if(matches.size()!=1)fail(DATASET_FORBIDDEN);
        var dataset = matches.get(0);
        var alias = table.getAlias();
        if (alias != null) reject(alias, "getAliasColumns");
        String key = alias == null ? dataset.schema().sqlName() : identifier(alias.getName());
        if (sources.putIfAbsent(key.toLowerCase(Locale.ROOT), new Source(key, dataset)) != null) fail(INVALID_SQL);
    }

    private static String identifier(String value) {
        if (value == null) throw new SqlQueryException(INVALID_SQL);
        if (value.startsWith("`") && value.endsWith("`") && value.length() >= 2) value = value.substring(1, value.length() - 1);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) fail(INVALID_SQL);
        return value;
    }
    static String quoted(String value) { return "`" + identifier(value) + "`"; }
    private static void fail(QueryIssue issue) { throw new SqlQueryException(issue); }
    private static void reject(Object node, String... getters) {
        for (String getter : getters) {
            try {
                Object value = node.getClass().getMethod(getter).invoke(node);
                if (value != null && !Boolean.FALSE.equals(value) && (!(value instanceof Collection<?> collection) || !collection.isEmpty()))
                    fail(UNSUPPORTED_SYNTAX);
            } catch (SqlQueryException safe) { throw safe; }
            catch (NoSuchMethodException notInThisAstVersion) { /* Only emitted AST properties can reach JDBC. */ }
            catch (ReflectiveOperationException incompatible) { fail(UNSUPPORTED_SYNTAX); }
        }
    }

    private final class Emitter {
        final SqlQuery query;
        final LinkedHashMap<String, Source> sources;
        final boolean bind;
        final int maximum;
        final List<Object> parameters = new ArrayList<>();
        final List<QueryColumn> columns = new ArrayList<>();
        final Map<String, TreeSet<String>> dependencies = new LinkedHashMap<>();
        final Map<String, QueryColumn> outputNames = new LinkedHashMap<>();
        int nodes;
        String currentDataset;
        Emitter(SqlQuery query, LinkedHashMap<String, Source> sources, boolean bind, int maximum) {
            this.query = query; this.sources = sources; this.bind = bind; this.maximum = maximum;
            for (String name : query.parameters().keySet()) {
                identifier(name);
                if (name.toLowerCase(Locale.ROOT).startsWith("current_") || name.startsWith("__")) fail(PARAMETER_INVALID);
            }
        }

        String select(PlainSelect select, List<Join> joins) {
            if (select.getSelectItems() == null || select.getSelectItems().isEmpty()) fail(INVALID_SQL);
            var items = new ArrayList<String>();
            for (var item : select.getSelectItems()) {
                var expression = item.getExpression();
                if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
                    if (item.getAlias() != null) fail(UNSUPPORTED_SYNTAX);
                    Collection<Source> chosen = sources.values();
                    if (expression instanceof AllTableColumns all) chosen = List.of(source(all.getTable().getName()));
                    for (var source : chosen) for (var field : source.dataset().schema().columns())
                        items.add(project(new Column(new Table(source.alias()), field.name()), null));
                } else {
                    if (item.getAlias() != null) reject(item.getAlias(), "getAliasColumns");
                    items.add(project(expression, item.getAlias() == null ? null : item.getAlias().getName()));
                }
            }
            if (columns.size() > 64) fail(RESULT_LIMIT);
            var sql = new StringBuilder("SELECT ");
            if (bind) sql.append("/*+ MAX_EXECUTION_TIME(10000) */ ");
            if (select.getDistinct() != null) sql.append("DISTINCT ");
            sql.append(String.join(", ", items)).append(" FROM ").append(from(select.getFromItem()));
            for (var join : joins) {
                sql.append(join.isLeft() ? " LEFT JOIN " : " INNER JOIN ").append(from(join.getRightItem())).append(" ON ");
                sql.append(String.join(" AND ", join.getOnExpressions().stream().map(e -> expr(e, false, 0)).toList()));
            }
            if (select.getWhere() != null) sql.append(" WHERE ").append(expr(select.getWhere(), false, 0));
            if (select.getGroupBy() != null) {
                var group = select.getGroupBy();
                if (group.isMysqlWithRollup() || group.getGroupingSets() != null && !group.getGroupingSets().isEmpty()
                        || group.getGroupByExpressionList() == null || group.getGroupByExpressionList().size() > 16) fail(UNSUPPORTED_SYNTAX);
                var values = new ArrayList<String>();
                for (Object value : group.getGroupByExpressionList()) values.add(expr((Expression) value, true, 0));
                sql.append(" GROUP BY ").append(String.join(", ", values));
            }
            if (select.getHaving() != null) sql.append(" HAVING ").append(expr(select.getHaving(), true, 0));
            if (select.getOrderByElements() != null) {
                if (select.getOrderByElements().size() > 8) fail(RESULT_LIMIT);
                var values = new ArrayList<String>();
                for (var order : select.getOrderByElements()) {
                    if (order.getNullOrdering() != null || order.isMysqlWithRollup()) fail(UNSUPPORTED_SYNTAX);
                    values.add(expr(order.getExpression(), true, 0) + (order.isAsc() ? " ASC" : " DESC"));
                }
                sql.append(" ORDER BY ").append(String.join(", ", values));
            }
            if (bind) { sql.append(" LIMIT ?"); parameters.add(maximum + 1); }
            else sql.append(" LIMIT ").append(maximum);
            return sql.toString();
        }

        String project(Expression expression, String requestedAlias) {
            if (columns.size() >= 64) fail(RESULT_LIMIT);
            String value = expr(expression, false, 0);
            String name = requestedAlias == null ? expression instanceof Column column
                    ? field(column).name() : "value_" + (columns.size() + 1) : identifier(requestedAlias);
            if (SqlDatasetRegistry.secret(name,"")) fail(FIELD_FORBIDDEN);
            QueryColumn column = expression instanceof Column c ? field(c) : new QueryColumn(name, name, type(expression), true);
            column = new QueryColumn(name, requestedAlias == null || expression instanceof Column c && name.equalsIgnoreCase(field(c).name())
                    ? column.label() : name, column.type(), column.nullable());
            if (outputNames.putIfAbsent(name.toLowerCase(Locale.ROOT), column) != null) fail(INVALID_SQL);
            columns.add(column);
            return value + " AS " + quoted(name);
        }
        String type(Expression expression) {
            if (expression instanceof Column column) return field(column).type();
            if (expression instanceof Function function) {
                String name = function.getName().toUpperCase(Locale.ROOT);
                if (name.equals("COUNT") || name.equals("LENGTH") || name.equals("CHAR_LENGTH")) return "INTEGER";
                if (Set.of("SUM", "AVG").contains(name)) return "DECIMAL";
                if (Set.of("LOWER", "UPPER").contains(name)) return "STRING";
                return function.getParameters() == null || function.getParameters().isEmpty() ? "STRING"
                        : type(function.getParameters().get(0));
            }
            if (expression instanceof LongValue) return "INTEGER";
            if (expression instanceof DoubleValue || expression instanceof BinaryExpression) return "DECIMAL";
            return "STRING";
        }

        String from(FromItem item) {
            var table = (Table) item;
            String alias = table.getAlias() == null ? identifier(table.getName()) : identifier(table.getAlias().getName());
            var source = source(alias);
            if (!bind) return quoted(source.dataset().schema().sqlName()) + " AS " + quoted(source.alias());
            parameters.addAll(source.dataset().sourceParameters());
            return "(" + source.dataset().sourceSql() + ") AS " + quoted(source.alias());
        }
        Source source(String alias) {
            var value = sources.get(identifier(alias).toLowerCase(Locale.ROOT));
            if (value == null) fail(FIELD_FORBIDDEN);
            return value;
        }
        Source columnSource(Column column) {
            if (column.getTable() != null && column.getTable().getName() != null && !column.getTable().getName().isBlank()) {
                reject(column.getTable(), "getSchemaName", "getDatabaseName", "getCatalogName");
                return source(column.getTable().getName());
            }
            String name = identifier(column.getColumnName());
            var matches = sources.values().stream().filter(s -> s.dataset().schema().columns().stream()
                    .anyMatch(c -> c.name().equalsIgnoreCase(name))).toList();
            if (matches.size() != 1) fail(FIELD_FORBIDDEN);
            return matches.get(0);
        }
        QueryColumn field(Column column) {
            String name = identifier(column.getColumnName());
            var source = columnSource(column);
            var field = source.dataset().schema().columns().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst()
                    .orElseThrow(() -> new SqlQueryException(FIELD_FORBIDDEN));
            dependencies.computeIfAbsent(source.dataset().schema().datasetKey(), key -> new TreeSet<>()).add(field.name());
            return field;
        }
        void currentRecord(Expression expression) {
            if (!(expression instanceof BinaryExpression binary)) return;
            Expression parameter = binary.getRightExpression();
            Expression column = binary.getLeftExpression();
            if (parameter instanceof Column && column instanceof JdbcNamedParameter) { var swap = column; column = parameter; parameter = swap; }
            if (parameter instanceof JdbcNamedParameter named && "current_record_id".equals(named.getName())) {
                if (!(column instanceof Column)) fail(PARAMETER_INVALID);
                var owner = columnSource((Column) column);
                String name = field((Column) column).name();
                if (owner.dataset().primaryKey().size() != 1 || !owner.dataset().primaryKey().contains(name)) fail(PARAMETER_INVALID);
                String key = owner.dataset().schema().datasetKey();
                if (currentDataset != null && !currentDataset.equals(key)) fail(PARAMETER_INVALID);
                currentDataset = key;
            }
        }

        String expr(Expression expression, boolean allowOutput, int depth) {
            if (expression == null || depth > 16 || ++nodes > 512) fail(UNSUPPORTED_SYNTAX);
            if (expression instanceof Column column) {
                String name = identifier(column.getColumnName());
                if (allowOutput && (column.getTable() == null || column.getTable().getName() == null)
                        && outputNames.containsKey(name.toLowerCase(Locale.ROOT))) return quoted(name);
                QueryColumn field = field(column);
                return quoted(columnSource(column).alias()) + "." + quoted(field.name());
            }
            if (expression instanceof NullValue) return "NULL";
            if (expression instanceof StringValue value) {
                if (value.getPrefix() != null && !value.getPrefix().isEmpty()) fail(UNSUPPORTED_SYNTAX);
                return literal(value.getNotExcapedValue(), value.toString());
            }
            if (expression instanceof LongValue || expression instanceof DoubleValue) {
                String text = expression.toString();
                if (text.length() > 64) fail(PARAMETER_INVALID);
                return literal(new BigDecimal(text), text);
            }
            if (expression instanceof JdbcNamedParameter named) {
                String name = identifier(named.getName());
                if (!bind) return ":" + name;
                Object value;
                if (TRUSTED.contains(name)) {
                    if (name.equals("current_record_id") && currentDataset == null) fail(PARAMETER_INVALID);
                    value = new TrustedValue(name, currentDataset);
                } else {
                    if (!query.parameters().containsKey(name)) fail(PARAMETER_INVALID);
                    value = query.parameters().get(name);
                    if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) fail(PARAMETER_INVALID);
                    if (value instanceof String text && text.length() > 2048) fail(PARAMETER_INVALID);
                }
                parameters.add(value);
                return "?";
            }
            if (expression instanceof SignedExpression signed) {
                if (signed.getSign() != '+' && signed.getSign() != '-') fail(UNSUPPORTED_SYNTAX);
                return "(" + signed.getSign() + expr(signed.getExpression(), allowOutput, depth + 1) + ")";
            }
            if (expression instanceof ParenthesedExpressionList<?> list && list.size() == 1)
                return "(" + expr(list.get(0), allowOutput, depth + 1) + ")";
            if (expression instanceof NotExpression not) return "(NOT " + expr(not.getExpression(), allowOutput, depth + 1) + ")";
            if (expression instanceof IsNullExpression isNull) return "(" + expr(isNull.getLeftExpression(), allowOutput, depth + 1)
                    + (isNull.isNot() ? " IS NOT NULL)" : " IS NULL)");
            if (expression instanceof LikeExpression like) {
                if (like.isCaseInsensitive() || like.isUseBinary() || !"LIKE".equals(like.getLikeKeyWord().name())) fail(UNSUPPORTED_SYNTAX);
                String left = expr(like.getLeftExpression(), allowOutput, depth + 1);
                String right = expr(like.getRightExpression(), allowOutput, depth + 1);
                String escape = "";
                if (like.getEscape() != null) {
                    if (!(like.getEscape() instanceof StringValue value) || value.getNotExcapedValue().length() != 1) fail(UNSUPPORTED_SYNTAX);
                    escape = " ESCAPE " + expr(like.getEscape(), allowOutput, depth + 1);
                }
                return "(" + left + (like.isNot() ? " NOT LIKE " : " LIKE ") + right + escape + ")";
            }
            if (expression instanceof Between between)
                return "(" + expr(between.getLeftExpression(), allowOutput, depth + 1)
                        + (between.isNot() ? " NOT BETWEEN " : " BETWEEN ")
                        + expr(between.getBetweenExpressionStart(), allowOutput, depth + 1) + " AND "
                        + expr(between.getBetweenExpressionEnd(), allowOutput, depth + 1) + ")";
            if (expression instanceof InExpression in) {
                if (!(in.getRightExpression() instanceof ExpressionList<?> list) || list.isEmpty() || list.size() > 32
                        || in.isGlobal() || in.getOldOracleJoinSyntax() != 0 || in.getOraclePriorPosition() != 0) fail(UNSUPPORTED_SYNTAX);
                var list = (ExpressionList<?>) in.getRightExpression();
                String left = expr(in.getLeftExpression(), allowOutput, depth + 1);
                var values = list.stream().map(value -> expr(value, allowOutput, depth + 1)).toList();
                return "(" + left + (in.isNot() ? " NOT IN (" : " IN (") + String.join(", ", values) + "))";
            }
            if (expression instanceof Function function) {
                String name = function.getName().toUpperCase(Locale.ROOT);
                if (!FUNCTIONS.contains(name) || function.getMultipartName().size() != 1) fail(UNSUPPORTED_SYNTAX);
                reject(function, "getNamedParameters", "getAttribute", "getKeep", "getLimit", "getHavingClause",
                        "getExtraKeyword", "getOrderByElements", "getOnOverflowTruncate", "getNullHandling", "isEscaped", "isUnique");
                var values = new ArrayList<String>();
                if (function.isAllColumns()) {
                    if (!name.equals("COUNT")) fail(UNSUPPORTED_SYNTAX);
                    values.add("*");
                } else if (function.getParameters() != null) for (var value : function.getParameters()) {
                    if (value instanceof AllColumns && name.equals("COUNT")) values.add("*");
                    else values.add(expr(value, allowOutput, depth + 1));
                }
                if (values.isEmpty() || values.size() > 8 || (!name.equals("COALESCE") && values.size() != 1)) fail(UNSUPPORTED_SYNTAX);
                return name + "(" + (function.isDistinct() ? "DISTINCT " : "") + String.join(", ", values) + ")";
            }
            if (expression instanceof BinaryExpression binary) {
                currentRecord(expression);
                String operator;
                if (binary instanceof EqualsTo) operator = "=";
                else if (binary instanceof NotEqualsTo) operator = "<>";
                else if (binary instanceof GreaterThan) operator = ">";
                else if (binary instanceof GreaterThanEquals) operator = ">=";
                else if (binary instanceof MinorThan) operator = "<";
                else if (binary instanceof MinorThanEquals) operator = "<=";
                else if (binary instanceof AndExpression) operator = "AND";
                else if (binary instanceof OrExpression) operator = "OR";
                else if (binary instanceof Addition) operator = "+";
                else if (binary instanceof Subtraction) operator = "-";
                else if (binary instanceof Multiplication) operator = "*";
                else if (binary instanceof Division) operator = "/";
                else fail(UNSUPPORTED_SYNTAX);
                // Compiler cannot fall through with an unknown binary operator.
                operator = binary instanceof EqualsTo ? "=" : binary.getStringExpression();
                if (!Set.of("=", "<>", "!=", ">", ">=", "<", "<=", "AND", "OR", "+", "-", "*", "/").contains(operator)) fail(UNSUPPORTED_SYNTAX);
                return "(" + expr(binary.getLeftExpression(), allowOutput, depth + 1) + " " + operator + " "
                        + expr(binary.getRightExpression(), allowOutput, depth + 1) + ")";
            }
            fail(UNSUPPORTED_SYNTAX);
            return "";
        }
        String literal(Object value, String display) {
            if (!bind) return display;
            parameters.add(value);
            return "?";
        }
    }
}
