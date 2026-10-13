package com.oltpbenchmark.benchmarks.kvpredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.oltpbenchmark.WorkloadConfiguration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.commons.configuration2.XMLConfiguration;
import org.junit.Test;

public class TestKvPredicateAnomaly {
  @Test
  public void testRandomPredicatesIncludeRanges() {
    for (int keyCount : new int[] {1, 5000}) {
      XMLConfiguration xml = new XMLConfiguration();
      xml.setProperty("keyCount", keyCount);
      xml.setProperty("minTxnLength", 10);
      xml.setProperty("maxTxnLength", 10);
      xml.setProperty("predicateReadRatio", 100.0);
      xml.setProperty("predicateRangeEnabled", true);
      WorkloadConfiguration configuration = new WorkloadConfiguration();
      configuration.setBenchmarkName("kvpredicate");
      configuration.setTerminals(1);
      configuration.setXmlConfig(xml);
      KvPredicateBenchmark benchmark = new KvPredicateBenchmark(configuration);
      Random random = new Random(41L);
      int rangeCount = 0;
      Set<String> ranges = new HashSet<>();
      for (int transaction = 0; transaction < 100; transaction++) {
        for (KvPredicateOperation operation : benchmark.nextTransaction(0, random)) {
          assertEquals(KvPredicateOperation.Kind.PREDICATE, operation.kind);
          if (operation.predicateKind == KvPredicateOperation.PredicateKind.RANGE) {
            assertTrue(operation.lowerBound >= 0L);
            assertTrue(operation.lowerBound < operation.upperBound);
            assertTrue(operation.upperBound <= keyCount);
            ranges.add(operation.lowerBound + ":" + operation.upperBound);
            if (keyCount == 1) {
              assertEquals(0L, operation.lowerBound);
              assertEquals(1L, operation.upperBound);
            }
            rangeCount++;
          }
        }
      }
      assertTrue("random predicates must include ranges", rangeCount > 0);
      if (keyCount > 1) {
        assertTrue("range bounds must vary between predicate reads", ranges.size() > 1);
      }
    }
  }

  @Test
  public void testRandomPredicatesExcludeRangesByDefault() {
    assertOnlyOriginalPredicates(-1.0, null);
  }

  @Test
  public void testRandomPredicatesExcludeRangesWhenDisabled() {
    assertOnlyOriginalPredicates(-1.0, false);
  }

  @Test
  public void testConfiguredReadsExcludeRangesByDefault() {
    assertOnlyOriginalPredicates(100.0, null);
  }

  @Test
  public void testConfiguredReadsExcludeRangesWhenDisabled() {
    assertOnlyOriginalPredicates(100.0, false);
  }

  @Test
  public void testRangeRejectsInvalidBounds() {
    assertThrows(
        IllegalArgumentException.class, () -> KvPredicateOperation.predicateRange(90L, 90L));
    assertThrows(
        IllegalArgumentException.class, () -> KvPredicateOperation.predicateRange(100L, 90L));
  }

  @Test
  public void testTotalReadsIncludeConfiguredPredicateShare() {
    for (int transactionsPerSession : new int[] {100, 200, 300, 400, 500}) {
      for (double readRatio : new double[] {30.0, 50.0, 95.0}) {
        for (double predicateShare : new double[] {0.0, 0.05, 0.10, 0.15, 0.20}) {
          XMLConfiguration xml = new XMLConfiguration();
          xml.setProperty("keyCount", 5000);
          xml.setProperty("keyDist", "uniform");
          xml.setProperty("minTxnLength", 10);
          xml.setProperty("maxTxnLength", 10);
          xml.setProperty("maxWritesPerKey", Integer.MAX_VALUE);
          xml.setProperty("readRatio", readRatio);
          xml.setProperty("predicateReadRatio", readRatio * predicateShare);
          WorkloadConfiguration configuration = new WorkloadConfiguration();
          configuration.setBenchmarkName("kvpredicate");
          configuration.setTerminals(20);
          configuration.setXmlConfig(xml);
          KvPredicateBenchmark benchmark = new KvPredicateBenchmark(configuration);
          Random random = new Random(41L);
          int reads = 0;
          int predicates = 0;
          int writes = 0;
          for (int transaction = 0; transaction < 20 * transactionsPerSession; transaction++) {
            List<KvPredicateOperation> operations =
                benchmark.nextTransaction(transaction % 20, random);
            assertEquals(10, operations.size());
            for (KvPredicateOperation operation : operations) {
              if (operation.kind == KvPredicateOperation.Kind.READ) {
                reads++;
              } else if (operation.kind == KvPredicateOperation.Kind.PREDICATE) {
                predicates++;
              } else if (operation.kind == KvPredicateOperation.Kind.WRITE) {
                writes++;
              }
            }
          }
          int operationCount = 200 * transactionsPerSession;
          int expectedReads = (int) Math.round(operationCount * readRatio / 100.0);
          assertEquals(expectedReads, reads + predicates);
          assertEquals((int) Math.round(expectedReads * predicateShare), predicates);
          assertEquals(operationCount - expectedReads, writes);
        }
      }
    }
  }

  @Test
  public void testInjectedAndControlUseSameSeededLayout() {
    KvPredicateBenchmark injected = benchmark("injected", true, 5.0, 10, 15);
    KvPredicateBenchmark control = benchmark("control", true, 5.0, 10, 15);

    List<KvPredicateOperation> injectedLeft = injected.nextTransaction(0, new Random(1L));
    List<KvPredicateOperation> injectedRight = injected.nextTransaction(1, new Random(2L));
    List<KvPredicateOperation> controlLeft = control.nextTransaction(0, new Random(1L));
    List<KvPredicateOperation> controlRight = control.nextTransaction(1, new Random(2L));

    assertCore(injectedLeft, 0L, 1L, 3L);
    assertCore(injectedRight, 1L, 0L, 4L);
    assertCore(controlLeft, 0L, 1L, 3L);
    assertCore(controlRight, 1L, 2L, 4L);
    assertEquals(visibleKinds(injectedLeft), visibleKinds(controlLeft));
    assertEquals(visibleKinds(injectedRight), visibleKinds(controlRight));
    assertEquals(
        "Txn|kvpredicate-anomaly:write-skew:injected:left:17:true",
        injected.traceTransactionType("Txn", injectedLeft));
    assertEquals(
        "Txn|kvpredicate-anomaly:write-skew:control:right:17:true",
        control.traceTransactionType("Txn", controlRight));
  }

  @Test
  public void testBackgroundOperationsCannotTouchReservedKeys() {
    KvPredicateBenchmark predicateBenchmark = benchmark("injected", true, 100.0, 8, 4);
    predicateBenchmark.nextTransaction(0, new Random(1L));
    predicateBenchmark.nextTransaction(1, new Random(2L));
    for (KvPredicateOperation operation : predicateBenchmark.nextTransaction(0, new Random(3L))) {
      assertEquals(KvPredicateOperation.Kind.PREDICATE, operation.kind);
      assertEquals(KvPredicateOperation.PredicateKind.LESS_THAN, operation.predicateKind);
      assertEquals(0L, operation.target);
    }

    KvPredicateBenchmark pointBenchmark = benchmark("injected", true, 0.0, 8, 8);
    pointBenchmark.nextTransaction(0, new Random(1L));
    pointBenchmark.nextTransaction(1, new Random(2L));
    for (KvPredicateOperation operation : pointBenchmark.nextTransaction(0, new Random(4L))) {
      assertTrue(
          operation.kind == KvPredicateOperation.Kind.READ
              || operation.kind == KvPredicateOperation.Kind.WRITE);
      assertTrue(operation.key >= 5L);
    }
  }

  @Test
  public void testWriteSkewPredicatesCountTowardConfiguredRatio() {
    KvPredicateBenchmark benchmark = benchmark("injected", true, 0.20, 5000, 15);
    Random random = new Random(1L);
    int visibleOperationCount = 0;
    int predicateReadCount = 0;
    for (int transaction = 0; transaction < 2000; transaction++) {
      List<KvPredicateOperation> operations = benchmark.nextTransaction(transaction % 20, random);
      for (KvPredicateOperation operation : visibleOperations(operations)) {
        visibleOperationCount++;
        if (operation.kind == KvPredicateOperation.Kind.PREDICATE) {
          predicateReadCount++;
        }
      }
    }

    assertEquals(30000, visibleOperationCount);
    assertEquals(60, predicateReadCount);
  }

  @Test
  public void testWriteSkewConfigurationValidation() {
    assertThrows(IllegalArgumentException.class, () -> benchmark("injected", true, 5.0, 5, 15));
    assertThrows(IllegalArgumentException.class, () -> benchmark("injected", true, 5.0, 6, 1));
    assertThrows(IllegalArgumentException.class, () -> benchmark("unknown", true, 5.0, 6, 15));
  }

  @Test
  public void testControlledExperimentDimensionsAreExact() {
    XMLConfiguration xml = new XMLConfiguration();
    xml.setProperty("keyCount", 100);
    xml.setProperty("keyDist", "uniform");
    xml.setProperty("minTxnLength", 5);
    xml.setProperty("maxTxnLength", 5);
    xml.setProperty("maxWritesPerKey", 256);
    xml.setProperty("predicateGroupCount", 4);
    xml.setProperty("predicateTransactionRatio", 20.0);
    xml.setProperty("writesPerTransaction", 1);
    xml.setProperty("writeKeyCount", 10);
    xml.setProperty("predicateResultCardinality", 3);
    xml.setProperty("predicateAbsentResultRatio", 25.0);
    xml.setProperty("kvPredicateAnomaly", "none");

    WorkloadConfiguration configuration = new WorkloadConfiguration();
    configuration.setBenchmarkName("kvpredicate");
    configuration.setTerminals(2);
    configuration.setIsolationMode("TRANSACTION_SERIALIZABLE");
    configuration.setXmlConfig(xml);
    KvPredicateBenchmark benchmark = new KvPredicateBenchmark(configuration);

    int predicateTransactions = 0;
    int predicateCount = 0;
    int absentCount = 0;
    int returnedCardinalityThreeCount = 0;
    int writeCount = 0;
    Map<Long, Integer> writersByKey = new HashMap<>();
    Random random = new Random(11L);
    for (int transaction = 0; transaction < 100; transaction++) {
      boolean hasPredicate = false;
      for (KvPredicateOperation operation : benchmark.nextTransaction(transaction % 2, random)) {
        if (operation.kind == KvPredicateOperation.Kind.PREDICATE) {
          hasPredicate = true;
          predicateCount++;
          if (operation.target == 0L) {
            absentCount++;
          } else if (operation.target == 3L) {
            returnedCardinalityThreeCount++;
          }
        } else if (operation.kind == KvPredicateOperation.Kind.WRITE) {
          writeCount++;
          writersByKey.merge(operation.key, 1, Integer::sum);
        }
      }
      if (hasPredicate) {
        predicateTransactions++;
      }
    }

    assertEquals(20, predicateTransactions);
    assertEquals(20, predicateCount);
    assertEquals(5, absentCount);
    assertEquals(15, returnedCardinalityThreeCount);
    assertEquals(100, writeCount);
    assertEquals(10, writersByKey.size());
    for (Map.Entry<Long, Integer> entry : writersByKey.entrySet()) {
      assertTrue(entry.getKey() >= 3L && entry.getKey() < 13L);
      assertEquals(Integer.valueOf(10), entry.getValue());
    }
  }

  private static void assertOnlyOriginalPredicates(double readRatio, Boolean rangeEnabled) {
    XMLConfiguration xml = new XMLConfiguration();
    xml.setProperty("keyCount", 5000);
    xml.setProperty("minTxnLength", 10);
    xml.setProperty("maxTxnLength", 10);
    xml.setProperty("readRatio", readRatio);
    xml.setProperty("predicateReadRatio", 100.0);
    if (rangeEnabled != null) {
      xml.setProperty("predicateRangeEnabled", rangeEnabled);
    }
    WorkloadConfiguration configuration = new WorkloadConfiguration();
    configuration.setBenchmarkName("kvpredicate");
    configuration.setTerminals(1);
    configuration.setXmlConfig(xml);
    KvPredicateBenchmark benchmark = new KvPredicateBenchmark(configuration);
    Random random = new Random(41L);
    Set<KvPredicateOperation.PredicateKind> predicateKinds = new HashSet<>();
    for (int transaction = 0; transaction < 100; transaction++) {
      for (KvPredicateOperation operation : benchmark.nextTransaction(0, random)) {
        assertEquals(KvPredicateOperation.Kind.PREDICATE, operation.kind);
        predicateKinds.add(operation.predicateKind);
      }
    }
    assertEquals(
        "disabled ranges must leave all five original predicate kinds available",
        Set.of(
            KvPredicateOperation.PredicateKind.TRUE,
            KvPredicateOperation.PredicateKind.EQUALS,
            KvPredicateOperation.PredicateKind.MOD,
            KvPredicateOperation.PredicateKind.GREATER_THAN,
            KvPredicateOperation.PredicateKind.LESS_THAN),
        predicateKinds);
  }

  private static void assertCore(
      List<KvPredicateOperation> operations, long predicateTarget, long writeKey, long paddingKey) {
    List<KvPredicateOperation> visible = visibleOperations(operations);
    assertEquals(15, visible.size());
    int predicateIndex = -1;
    int writeIndex = -1;
    for (int index = 0; index < visible.size(); index++) {
      KvPredicateOperation operation = visible.get(index);
      if (operation.kind == KvPredicateOperation.Kind.PREDICATE) {
        assertEquals(-1, predicateIndex);
        predicateIndex = index;
        assertEquals(KvPredicateOperation.PredicateKind.EQUALS, operation.predicateKind);
        assertEquals(predicateTarget, operation.target);
      } else if (operation.kind == KvPredicateOperation.Kind.WRITE) {
        assertEquals(-1, writeIndex);
        writeIndex = index;
        assertEquals(writeKey, operation.key);
      } else {
        assertEquals(KvPredicateOperation.Kind.READ, operation.kind);
        assertEquals(paddingKey, operation.key);
      }
    }
    assertTrue(predicateIndex >= 0);
    assertTrue(writeIndex > predicateIndex);
  }

  private static List<KvPredicateOperation.Kind> visibleKinds(
      List<KvPredicateOperation> operations) {
    List<KvPredicateOperation.Kind> kinds = new ArrayList<>();
    for (KvPredicateOperation operation : visibleOperations(operations)) {
      kinds.add(operation.kind);
    }
    return kinds;
  }

  private static List<KvPredicateOperation> visibleOperations(
      List<KvPredicateOperation> operations) {
    List<KvPredicateOperation> visible = new ArrayList<>();
    for (KvPredicateOperation operation : operations) {
      if (operation.kind != KvPredicateOperation.Kind.BARRIER
          && operation.kind != KvPredicateOperation.Kind.SLEEP) {
        visible.add(operation);
      }
    }
    return visible;
  }

  private static KvPredicateBenchmark benchmark(
      String variant,
      boolean isolateBackground,
      double predicateRatio,
      int keyCount,
      int txnLength) {
    XMLConfiguration xml = new XMLConfiguration();
    xml.setProperty("keyCount", keyCount);
    xml.setProperty("keyDist", "uniform");
    xml.setProperty("keyDistBase", 2.0);
    xml.setProperty("minTxnLength", txnLength);
    xml.setProperty("maxTxnLength", txnLength);
    xml.setProperty("maxWritesPerKey", 256);
    xml.setProperty("predicateGroupCount", 4);
    xml.setProperty("predicateReadRatio", predicateRatio);
    xml.setProperty("kvPredicateAnomaly", "write-skew");
    xml.setProperty("kvPredicateAnomalyVariant", variant);
    xml.setProperty("kvPredicateAnomalySeed", 17L);
    xml.setProperty("kvPredicateAnomalyIsolateBackground", isolateBackground);
    xml.setProperty("kvPredicateAnomalyDelayMs", 0L);

    WorkloadConfiguration configuration = new WorkloadConfiguration();
    configuration.setBenchmarkName("kvpredicate");
    configuration.setTerminals(2);
    configuration.setIsolationMode("TRANSACTION_REPEATABLE_READ");
    configuration.setXmlConfig(xml);
    return new KvPredicateBenchmark(configuration);
  }
}
