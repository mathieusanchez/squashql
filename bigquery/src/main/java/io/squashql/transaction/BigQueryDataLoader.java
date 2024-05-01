package io.squashql.transaction;

import com.google.cloud.bigquery.*;
import io.squashql.BigQueryServiceAccountDatastore;
import io.squashql.BigQueryUtil;
import io.squashql.jackson.JacksonUtil;
import io.squashql.list.Lists;
import io.squashql.type.TableTypedField;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class BigQueryDataLoader implements DataLoader {

  // 1, 2, 4, 8, 16
  private static final int MAX_SLEEPS = 5;

  final BigQuery bigquery;
  final String datasetName;

  public BigQueryDataLoader(BigQuery bigquery, String datasetName) {
    this.bigquery = bigquery;
    this.datasetName = datasetName;
  }

  public BigQuery getBigQuery() {
    return this.bigquery;
  }

  public void dropAndCreateInMemoryTable(String tableName, List<TableTypedField> fields) {
    TableId tableId = TableId.of(this.datasetName, tableName);

    List<com.google.cloud.bigquery.Field> fieldList = fields.stream()
            .map(f -> {
              if (f.type().equals(Lists.LongList.class)) {
                return Field.of(f.name(), StandardSQLTypeName.INT64).toBuilder().setMode(Field.Mode.REPEATED).build();
              } else if (f.type().equals(Lists.StringList.class)) {
                return Field.of(f.name(), StandardSQLTypeName.STRING).toBuilder().setMode(Field.Mode.REPEATED).build();
              } else {
                return Field.of(f.name(), BigQueryUtil.classToBigQueryType(f.type()));
              }
            })
            .toList();
    // Table schema definition
    Schema schema = Schema.of(fieldList);
    TableDefinition tableDefinition = StandardTableDefinition.of(schema);
    TableInfo tableInfo = TableInfo.newBuilder(tableId, tableDefinition).build();

    try {
      this.bigquery.create(tableInfo);
    } catch (BigQueryException e) {
      if (e.getCode() == 409 && e.getReason().equals("duplicate")) {
        this.bigquery.delete(tableId);
        this.bigquery.create(tableInfo);
      } else {
        throw e;
      }
    }
  }

  @Override
  public void load(String store, List<Object[]> tuples) {
    List<TableTypedField> fields = BigQueryServiceAccountDatastore.getFieldsOrNull(this.bigquery, this.datasetName, store);
    List<InsertAllRequest.RowToInsert> list = new ArrayList<>();
    for (Object[] tuple : tuples) {
      Map<String, Object> m = new HashMap<>();
      for (int i = 0; i < fields.size(); i++) {
        TableTypedField field = fields.get(i);
        Object o = tuple[i];
        if (o != null) {
          if (o.getClass().equals(LocalDate.class) || o.getClass().equals(LocalDateTime.class)) {
            o = o.toString();
          } else if (field.type().equals(Object.class)) {
            o = JacksonUtil.serialize(o);
          }
        }
        m.put(field.name(), o);
      }
      list.add(InsertAllRequest.RowToInsert.of(m));
    }

    TableId tableId = TableId.of(this.datasetName, store);
    Table table = this.bigquery.getTable(tableId);

    int sleepTime = 1;// Start at 1 s.
    int attempt = 0;
    while (true) {
      // table creation is eventually consistent, try several time to insert it.
      // https://stackoverflow.com/questions/73544951/no-table-found-for-new-bigquery-table
      // Still issues even after this retry.
      try {
        InsertAllResponse response = table.insert(list);
        if (response.hasErrors()) {
          for (Map.Entry<Long, List<BigQueryError>> entry : response.getInsertErrors().entrySet()) {
            System.out.println("Response error: \n" + entry.getValue());
          }
          throw new RuntimeException("error while inserting rows, see above");
        }
        return;
      } catch (BigQueryException exception) {
        /*
         * See SLA https://cloud.google.com/bigquery/sla
         * "Back-off Requirements" means, when an error occurs, the Customer Application is responsible for waiting for
         * a period of time before issuing another request. This means that after the first error, there is a minimum
         * back-off interval of 1 second and for each consecutive error, the back-off interval increases exponentially
         * up to 32 seconds.
         */
        try {
          Thread.sleep(sleepTime * 1000);
        } catch (InterruptedException e) {
          log.error("", e);
          Thread.currentThread().interrupt();
        }
        if (attempt < MAX_SLEEPS) {
          sleepTime <<= 1;
          attempt++;
          log.info("table.insert, retry {}", attempt);
        } else {
          log.info("table.insert after " + MAX_SLEEPS + " attempts. Abort.");
          throw exception;
        }
      }
    }
  }

  @Override
  public void loadCsv(String table, String path, String delimiter, boolean header) {
    throw new RuntimeException("not impl. yet");
  }
}
