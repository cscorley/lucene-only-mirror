package org.apache.lucene.util.junitcompat;

import org.apache.lucene.util.LuceneTestCase;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

public class TestSystemPropertiesInvariantRule {
  public static final String PROP_KEY1 = "new-property-1";
  public static final String VALUE1 = "new-value-1";
  
  public static class Base extends LuceneTestCase {
    public void testEmpty() {}
  }
  
  public static class InBeforeClass extends Base {
    @BeforeClass
    public static void beforeClass() {
      System.setProperty(PROP_KEY1, VALUE1);
    }
  }
  
  public static class InAfterClass extends Base {
    @AfterClass
    public static void afterClass() {
      System.setProperty(PROP_KEY1, VALUE1);
    }
  }
  
  public static class InTestMethod extends Base {
    public void testMethod1() {
      if (System.getProperty(PROP_KEY1) != null) {
        throw new RuntimeException("Shouldn't be here.");
      }
      System.setProperty(PROP_KEY1, VALUE1);
    }
    
    public void testMethod2() {
      testMethod1();
    }
  }
  
  @Test
  public void testRuleInvariantBeforeClass() {
    Result runClasses = JUnitCore.runClasses(InBeforeClass.class);
    Assert.assertEquals(1, runClasses.getFailureCount());
    Assert.assertTrue(runClasses.getFailures().get(0).getMessage()
        .contains(PROP_KEY1));
    Assert.assertNull(System.getProperty(PROP_KEY1));
  }
  
  @Test
  public void testRuleInvariantAfterClass() {
    Result runClasses = JUnitCore.runClasses(InAfterClass.class);
    Assert.assertEquals(1, runClasses.getFailureCount());
    Assert.assertTrue(runClasses.getFailures().get(0).getMessage()
        .contains(PROP_KEY1));
    Assert.assertNull(System.getProperty(PROP_KEY1));
  }
  
  @Test
  public void testRuleInvariantInTestMethod() {
    Result runClasses = JUnitCore.runClasses(InTestMethod.class);
    Assert.assertEquals(2, runClasses.getFailureCount());
    for (Failure f : runClasses.getFailures()) {
      Assert.assertTrue(f.getMessage().contains(PROP_KEY1));
    }
    Assert.assertNull(System.getProperty(PROP_KEY1));
  }
}
