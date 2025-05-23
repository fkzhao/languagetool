/* LanguageTool, a natural language style checker
 * Copyright (C) 2014 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.rules.en;

import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.Languages;
import org.languagetool.rules.PartialPosTagFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A {@link PartialPosTagFilter} for English that also runs the disambiguator. Note
 * that the disambiguator is called with a single token, so only rules
 * will apply that have a single {@code <match>} element.
 * <b>Warning: Do not use this in disambiguation.xml, it would cause an endless loop,
 * use {@link NoDisambiguationEnglishPartialPosTagFilter} instead.</b> 
 *
 * @since 2.8
 * @see NoDisambiguationEnglishPartialPosTagFilter
 */
public class EnglishPartialPosTagFilter extends PartialPosTagFilter {

  @Override
  protected List<AnalyzedTokenReadings> tag(String token) {
    try {
      var englishLanguage = Languages.getLanguageForShortCode("en");
      List<AnalyzedTokenReadings> tags = englishLanguage.getTagger().tag(Collections.singletonList(token));
      AnalyzedTokenReadings[] atr = tags.toArray(new AnalyzedTokenReadings[tags.size()]);
      AnalyzedSentence disambiguated = englishLanguage.getDisambiguator().disambiguate(new AnalyzedSentence(atr));
      return Arrays.asList(disambiguated.getTokens());
    } catch (IOException e) {
      throw new RuntimeException("Could not tag and disambiguate '" + token + "'", e);
    }
  }
}
