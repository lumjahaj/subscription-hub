package dev.lumjahaj.subscription.hub.architecture;

import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build when the README's "Data model" mermaid diagram drifts from
 * the real schema — the same failure shape as the Testcontainers lifecycle bug
 * and the unpinned MinIO image (CLAUDE.md §5): correct when written, silently
 * wrong three migrations later, nobody looks. This is that "somebody looks".
 *
 * Extends AbstractIntegrationTest purely to reuse its already-running,
 * already-migrated Postgres singleton — no new container, no new
 * @TestPropertySource/@DynamicPropertySource (either would fork the context
 * cache and, with it, the "exactly one Postgres per build" invariant).
 */
class DataModelDiagramIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    /** Flyway's own bookkeeping table — deliberately not part of the domain diagram. */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    /**
     * README.md's "Data model" section draws TENANT connected only to the
     * tables nothing else owns (see the section's own explanatory sentence).
     * Every other tenant_id FK is real but intentionally undrawn to avoid a
     * hairball, so those parents are excluded here rather than left to fail.
     */
    private static final String UNDRAWN_FK_PARENT = "tenant";

    private static String readmeErDiagramBlock;

    @BeforeAll
    static void readDiagramBlock() throws IOException {
        // Surefire's working directory is the project basedir, so this is a
        // plain relative path, same as every other file-based test fixture.
        String readme = Files.readString(Path.of("README.md"));

        Matcher block = Pattern.compile("```mermaid\\s*\\R(erDiagram.*?)```", Pattern.DOTALL).matcher(readme);
        assertThat(block.find())
                .as("README.md must contain a ```mermaid erDiagram block documenting the data model")
                .isTrue();
        readmeErDiagramBlock = block.group(1);
    }

    @Test
    void everyTableIsInTheDiagram() throws SQLException {
        Set<String> diagramEntities = entitiesInDiagram();
        Set<String> missing = new HashSet<>();
        for (String table : tablesInSchema()) {
            if (!diagramEntities.contains(table.toUpperCase(Locale.ROOT))) {
                missing.add(table);
            }
        }
        assertThat(missing)
                .as("Table(s) exist in the schema but are missing from README.md's erDiagram block. "
                        + "A migration added a table nobody documented — add it to the diagram (and, "
                        + "unless it hangs off `tenant` like every other root table, draw its edges).")
                .isEmpty();
    }

    @Test
    void everyDiagramEntityIsARealTable() throws SQLException {
        Set<String> schemaTables = new HashSet<>();
        for (String table : tablesInSchema()) {
            schemaTables.add(table.toUpperCase(Locale.ROOT));
        }
        Set<String> stale = new HashSet<>();
        for (String entity : entitiesInDiagram()) {
            if (!schemaTables.contains(entity)) {
                stale.add(entity);
            }
        }
        assertThat(stale)
                .as("Entity/entities in README.md's erDiagram block no longer exist in the schema. "
                        + "A table was renamed or dropped and the diagram still shows the old name — "
                        + "update or remove it.")
                .isEmpty();
    }

    @Test
    void everyForeignKeyIsDrawn() throws SQLException {
        Set<UnorderedPair> drawnEdges = edgesInDiagram();
        Set<String> missing = new HashSet<>();
        for (ForeignKey fk : foreignKeysInSchema()) {
            // UNDRAWN_FK_PARENT: the README's own documented exclusion — see
            // the field javadoc. Everything else must have a drawn edge.
            if (fk.parentTable().equalsIgnoreCase(UNDRAWN_FK_PARENT)) {
                continue;
            }
            UnorderedPair pair = new UnorderedPair(
                    fk.childTable().toUpperCase(Locale.ROOT), fk.parentTable().toUpperCase(Locale.ROOT));
            if (!drawnEdges.contains(pair)) {
                missing.add(fk.childTable() + " -> " + fk.parentTable() + " (constraint " + fk.name() + ")");
            }
        }
        assertThat(missing)
                .as("Foreign key(s) exist in the schema with no matching edge in README.md's erDiagram block. "
                        + "A new relationship was added but the diagram wasn't updated — add a "
                        + "\"PARENT ||--o{ CHILD : ...\" line for each one listed here.")
                .isEmpty();
    }

    @Test
    void everyEnumValueIsListed() throws SQLException {
        Set<String> missing = new HashSet<>();
        for (EnumLabel label : enumLabelsInSchema()) {
            // Whole-word match so e.g. PAID (a label) can't be satisfied by
            // paid_at (a column name) appearing elsewhere in the block.
            Pattern wordBoundary = Pattern.compile("\\b" + Pattern.quote(label.value()) + "\\b");
            if (!wordBoundary.matcher(readmeErDiagramBlock).find()) {
                missing.add(label.enumType() + "." + label.value());
            }
        }
        assertThat(missing)
                .as("Enum value(s) exist in the database but aren't mentioned anywhere in README.md's "
                        + "erDiagram block. An `ALTER TYPE ... ADD VALUE` (like V2's PAUSED or V11's "
                        + "payment_status) needs the enum's comment in the diagram updated to list it.")
                .isEmpty();
    }

    // --- schema introspection -------------------------------------------------

    private Set<String> tablesInSchema() throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     select table_name from information_schema.tables
                     where table_schema = 'public' and table_type = 'BASE TABLE'
                     """)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                if (!table.equalsIgnoreCase(FLYWAY_HISTORY_TABLE)) {
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private Set<ForeignKey> foreignKeysInSchema() throws SQLException {
        Set<ForeignKey> fks = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     select conname, conrelid::regclass::text as child_table, confrelid::regclass::text as parent_table
                     from pg_constraint
                     where contype = 'f' and connamespace = 'public'::regnamespace
                     """)) {
            while (rs.next()) {
                fks.add(new ForeignKey(rs.getString("conname"), rs.getString("child_table"), rs.getString("parent_table")));
            }
        }
        return fks;
    }

    private Set<EnumLabel> enumLabelsInSchema() throws SQLException {
        Set<EnumLabel> labels = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     select t.typname as enum_type, e.enumlabel as label
                     from pg_type t
                     join pg_enum e on e.enumtypid = t.oid
                     join pg_namespace n on n.oid = t.typnamespace
                     where n.nspname = 'public'
                     """)) {
            while (rs.next()) {
                labels.add(new EnumLabel(rs.getString("enum_type"), rs.getString("label")));
            }
        }
        return labels;
    }

    // --- diagram parsing --------------------------------------------------

    private static final Pattern ENTITY_DECLARATION = Pattern.compile("^\\s*([A-Z_]+)\\s*\\{", Pattern.MULTILINE);

    private static final Pattern RELATIONSHIP_EDGE =
            Pattern.compile("^\\s*([A-Z_]+)\\s*[|}][|o]--[|o][|{]\\s*([A-Z_]+)\\s*:", Pattern.MULTILINE);

    private static Set<String> entitiesInDiagram() {
        Set<String> entities = new HashSet<>();
        Matcher m = ENTITY_DECLARATION.matcher(readmeErDiagramBlock);
        while (m.find()) {
            entities.add(m.group(1));
        }
        return entities;
    }

    private static Set<UnorderedPair> edgesInDiagram() {
        Set<UnorderedPair> edges = new HashSet<>();
        Matcher m = RELATIONSHIP_EDGE.matcher(readmeErDiagramBlock);
        while (m.find()) {
            edges.add(new UnorderedPair(m.group(1), m.group(2)));
        }
        return edges;
    }

    private record ForeignKey(String name, String childTable, String parentTable) {
    }

    private record EnumLabel(String enumType, String value) {
    }

    /** A relationship's direction in the DDL (child -> parent) doesn't match how it reads left-to-right in the diagram. */
    private record UnorderedPair(String a, String b) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof UnorderedPair other)) {
                return false;
            }
            return (a.equals(other.a) && b.equals(other.b)) || (a.equals(other.b) && b.equals(other.a));
        }

        @Override
        public int hashCode() {
            return a.hashCode() + b.hashCode();
        }
    }
}
