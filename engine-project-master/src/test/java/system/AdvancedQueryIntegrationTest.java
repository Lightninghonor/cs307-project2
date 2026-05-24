package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.system.TransactionManager;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdvancedQueryIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Advanced SELECT supports max/min, group by, order by, in/not in, and exists")
    void advancedSelectFeaturesWork() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE t (id int, age int, gpa double, name varchar)");
        executeStatement(dbManager, "CREATE TABLE d (student_id int, title varchar)");
        executeStatement(dbManager, "INSERT INTO t (id, age, gpa, name) VALUES (1, 20, 3.2, 'a')");
        executeStatement(dbManager, "INSERT INTO t (id, age, gpa, name) VALUES (2, 20, 3.5, 'b')");
        executeStatement(dbManager, "INSERT INTO t (id, age, gpa, name) VALUES (3, 21, 2.8, 'c')");
        executeStatement(dbManager, "INSERT INTO t (id, age, gpa, name) VALUES (4, 21, 3.7, 'd')");
        executeStatement(dbManager, "INSERT INTO t (id, age, gpa, name) VALUES (5, 22, 3.1, 'e')");
        executeStatement(dbManager, "INSERT INTO d (student_id, title) VALUES (2, 'x')");
        executeStatement(dbManager, "INSERT INTO d (student_id, title) VALUES (4, 'y')");

        List<List<Value>> aggregates = queryRows(dbManager, "SELECT max(t.gpa), min(t.age) FROM t");
        assertThat(aggregates).hasSize(1);
        assertThat(aggregates.get(0).get(0).value).isEqualTo(3.7);
        assertThat(aggregates.get(0).get(1).value).isEqualTo(20L);

        List<List<Value>> grouped = queryRows(dbManager,
                "SELECT t.age, count(*), max(t.gpa), min(t.id) FROM t GROUP BY t.age ORDER BY t.age");
        assertThat(grouped).hasSize(3);
        assertThat(grouped).extracting(row -> row.get(0).value).containsExactly(20L, 21L, 22L);
        assertThat(grouped).extracting(row -> row.get(1).value).containsExactly(2L, 2L, 1L);
        assertThat(grouped).extracting(row -> row.get(2).value).containsExactly(3.5, 3.7, 3.1);
        assertThat(grouped).extracting(row -> row.get(3).value).containsExactly(1L, 3L, 5L);

        assertThat(queryRows(dbManager, "SELECT t.id FROM t ORDER BY t.id DESC"))
                .extracting(row -> row.get(0).value)
                .containsExactly(5L, 4L, 3L, 2L, 1L);

        assertThat(queryRows(dbManager, "SELECT t.id AS sid FROM t ORDER BY sid DESC"))
                .extracting(row -> row.get(0).value)
                .containsExactly(5L, 4L, 3L, 2L, 1L);

        assertThat(queryRows(dbManager, "SELECT t.id FROM t WHERE t.id IN (1, 3, 5) ORDER BY t.id"))
                .extracting(row -> row.get(0).value)
                .containsExactly(1L, 3L, 5L);

        assertThat(queryRows(dbManager,
                "SELECT t.id FROM t WHERE t.id IN (SELECT d.student_id FROM d) ORDER BY t.id"))
                .extracting(row -> row.get(0).value)
                .containsExactly(2L, 4L);

        assertThat(queryRows(dbManager,
                "SELECT t.id FROM t WHERE t.id NOT IN (SELECT d.student_id FROM d) AND t.id <= 4 ORDER BY t.id"))
                .extracting(row -> row.get(0).value)
                .containsExactly(1L, 3L);

        assertThat(queryRows(dbManager,
                "SELECT t.id FROM t WHERE EXISTS (SELECT d.student_id FROM d WHERE d.student_id = t.id) ORDER BY t.id"))
                .extracting(row -> row.get(0).value)
                .containsExactly(2L, 4L);

        assertThat(queryRows(dbManager,
                "SELECT t.id FROM t WHERE NOT EXISTS (SELECT d.student_id FROM d WHERE d.student_id = t.id) AND t.id <= 3 ORDER BY t.id"))
                .extracting(row -> row.get(0).value)
                .containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("ALTER TABLE ADD/DROP COLUMN works on empty tables and keeps record layout usable")
    void alterTableOnEmptyTablesWorks() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE add_t (id int)");
        assertThatCode(() -> executeStatement(dbManager, "ALTER TABLE add_t ADD name varchar")).doesNotThrowAnyException();
        assertThat(dbManager.getMetaManager().getTable("add_t").columns_list)
                .extracting(column -> column.name)
                .containsExactly("id", "name");
        executeStatement(dbManager, "INSERT INTO add_t (id, name) VALUES (1, 'one')");
        assertThat(queryRows(dbManager, "SELECT add_t.id, add_t.name FROM add_t"))
                .extracting(row -> List.of(row.get(0).value, row.get(1).value))
                .containsExactly(List.of(1L, "one"));

        assertThatThrownBy(() -> executeStatement(dbManager, "ALTER TABLE add_t DROP COLUMN name"))
                .isInstanceOf(DBException.class);

        executeStatement(dbManager, "CREATE TABLE drop_t (id int, flag int)");
        assertThatCode(() -> executeStatement(dbManager, "ALTER TABLE drop_t DROP COLUMN flag"))
                .doesNotThrowAnyException();
        assertThat(dbManager.getMetaManager().getTable("drop_t").columns_list)
                .extracting(column -> column.name)
                .containsExactly("id");
        executeStatement(dbManager, "INSERT INTO drop_t (id) VALUES (10)");
        assertThat(queryRows(dbManager, "SELECT drop_t.id FROM drop_t"))
                .extracting(row -> row.get(0).value)
                .containsExactly(10L);
    }

    private DBManager buildDbManager() throws DBException {
        HashMap<String, Integer> fileOffsets = new HashMap<>();
        DiskManager diskManager = new DiskManager(tempDir.toString(), fileOffsets);
        IntFunction<PageReplacer> replacerFactory = ClockReplacer::new;
        BufferPool bufferPool = new BufferPool(32, diskManager, replacerFactory.apply(32));
        RecordManager recordManager = new RecordManager(diskManager, bufferPool);
        MetaManager metaManager = new MetaManager(tempDir.resolve("meta").toString());
        DBManager dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager, null,
                replacerFactory);
        dbManager.setTransactionManager(new TransactionManager(dbManager));
        return dbManager;
    }

    private void executeStatement(DBManager dbManager, String sql) throws DBException {
        queryRows(dbManager, sql);
    }

    private List<List<Value>> queryRows(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (logicalOperator == null) {
            return List.of();
        }

        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        List<List<Value>> rows = new ArrayList<>();
        physicalOperator.Begin();
        try {
            while (physicalOperator.hasNext()) {
                physicalOperator.Next();
                Tuple tuple = physicalOperator.Current();
                if (tuple != null) {
                    rows.add(List.of(tuple.getValues()));
                }
            }
        } finally {
            physicalOperator.Close();
            dbManager.getBufferPool().FlushAllPages("");
        }
        return rows;
    }
}
