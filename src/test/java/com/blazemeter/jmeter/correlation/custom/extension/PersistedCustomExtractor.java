package com.blazemeter.jmeter.correlation.custom.extension;

import com.blazemeter.jmeter.correlation.core.BaseCorrelationContext;
import com.blazemeter.jmeter.correlation.core.extractors.RegexCorrelationExtractor;
import com.blazemeter.jmeter.correlation.gui.CorrelationRuleTestElement;
import com.google.common.annotations.VisibleForTesting;

/**
 * Custom extractor that behaves as the regex one and stores its class in the rule test element
 * (as any well implemented extension does), used to test rules whose components are not found by
 * the search of components of the registry.
 */
@VisibleForTesting
public class PersistedCustomExtractor<T extends BaseCorrelationContext> extends
    RegexCorrelationExtractor<T> {

  public PersistedCustomExtractor() {
    super();
  }

  public PersistedCustomExtractor(String regex) {
    super(regex);
  }

  @Override
  public String getDisplayName() {
    return "Persisted Custom";
  }

  @Override
  public void updateTestElem(CorrelationRuleTestElement testElem) {
    super.updateTestElem(testElem);
  }

  @Override
  public void update(CorrelationRuleTestElement testElem) {
    super.update(testElem);
  }
}
