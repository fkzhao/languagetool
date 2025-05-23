/* LanguageTool, a natural language style checker
 * Copyright (C) 2016 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.opentelemetry.api.common.Attributes;
import org.jetbrains.annotations.NotNull;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.Languages;
import org.languagetool.Premium;
import org.languagetool.markup.AnnotatedText;
import org.languagetool.markup.AnnotatedTextBuilder;
import org.languagetool.rules.CorrectExample;
import org.languagetool.rules.IncorrectExample;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleOption;
import org.languagetool.rules.TextLevelRule;
import org.languagetool.tools.TelemetryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.languagetool.server.LanguageToolHttpHandler.API_DOC_URL;

/**
 * Handle requests to {@code /v2/} of the HTTP API. 
 * @since 3.4
 */
class ApiV2 {

  private static final Logger logger = LoggerFactory.getLogger(ApiV2.class);

  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String TEXT_CONTENT_TYPE = "text/plain";
  private static final String ENCODING = "UTF-8";

  private final TextChecker textChecker;
  private final String allowOriginUrl;
  private final JsonFactory factory = new JsonFactory();

  ApiV2(TextChecker textChecker, String allowOriginUrl) {
    this.textChecker = textChecker;
    this.allowOriginUrl = allowOriginUrl;
  }

  void handleRequest(String path, HttpExchange httpExchange, Map<String, String> parameters, ErrorRequestLimiter errorRequestLimiter,
                     String remoteAddress, HTTPServerConfig config) throws Exception {
    String spanName = "/v2/" + path;
    if (path.equals("languages")) {
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> handleLanguagesRequest(httpExchange));
    } else if (path.equals("maxtextlength")) {
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> handleMaxTextLengthRequest(httpExchange, config));
    } else if (path.equals("configinfo")) {
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> handleGetConfigurationInfoRequest(httpExchange, parameters, config));
    } else if (path.equals("info")) {
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> handleSoftwareInfoRequest(httpExchange));
    } else if (path.equals("check")) {
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> handleCheckRequest(httpExchange, parameters, errorRequestLimiter, remoteAddress, config));
    } else if (path.equals("words")) {
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> handleWordsRequest(httpExchange, parameters, config));
    } else if (path.equals("words/add")) {
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> handleWordAddRequest(httpExchange, parameters, config));
    } else if (path.equals("words/delete")) {
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> handleWordDeleteRequest(httpExchange, parameters, config));
    //} else if (path.equals("rule/examples")) {
    //  // private (i.e. undocumented) API for our own use only
    //  handleRuleExamplesRequest(httpExchange, parameters);
    } else if (path.equals("admin/refreshUser")) {
      // private (i.e. undocumented) API for our own use only
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> handleRefreshUserInfoRequest(httpExchange, parameters, config));
    } else if (path.equals("users/me")) {
      // private (i.e. undocumented) API for our own use only
      TelemetryProvider.INSTANCE.createSpan(spanName, Attributes.empty(), () -> 
        handleGetUserInfoRequest(httpExchange, parameters, config));
    } else {
      throw new PathNotFoundException("Unsupported action: '" + path + "'. Please see " + API_DOC_URL);
    }
  }

  private void handleLanguagesRequest(HttpExchange httpExchange) throws IOException {
    String response = getLanguages();
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private void handleMaxTextLengthRequest(HttpExchange httpExchange, HTTPServerConfig config) throws IOException {
    String response = Integer.toString(config.getMaxTextLengthAnonymous());
    ServerTools.setCommonHeaders(httpExchange, TEXT_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private void handleGetConfigurationInfoRequest(HttpExchange httpExchange, Map<String, String> parameters, HTTPServerConfig config) throws IOException {
    if (parameters.get("language") == null) {
      throw new BadRequestException("'language' parameter missing");
    }
    Language lang = Languages.getLanguageForShortCode(parameters.get("language"));
    String response = getConfigurationInfo(lang, config);
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private void handleSoftwareInfoRequest(HttpExchange httpExchange) throws IOException {
    String response = getSoftwareInfo();
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private void handleCheckRequest(HttpExchange httpExchange, Map<String, String> parameters, ErrorRequestLimiter errorRequestLimiter, String remoteAddress, HTTPServerConfig config) throws Exception {
    AnnotatedText aText;
    if (parameters.containsKey("text") && parameters.containsKey("data")) {
      throw new BadRequestException("Set only 'text' or 'data' parameter, not both");
    } else if (parameters.containsKey("text")) {
      aText = new AnnotatedTextBuilder().addText(parameters.get("text")).build();
    } else if (parameters.containsKey("data")) {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode data;
      try {
        data = mapper.readTree(parameters.get("data"));
      } catch (JsonProcessingException e) {
        throw new BadRequestException("Could not parse JSON from 'data' parameter", e);
      }
      if (data.get("text") != null && data.get("annotation") != null) {
        throw new BadRequestException("'data' key in JSON requires either 'text' or 'annotation' key, not both");
      } else if (data.get("text") != null) {
        aText = getAnnotatedTextFromString(data, data.get("text").asText());
      } else if (data.get("annotation") != null) {
        aText = getAnnotatedTextFromJson(data);
      } else {
        throw new BadRequestException("'data' key in JSON requires 'text' or 'annotation' key");
      }
    } else {
      throw new BadRequestException("Missing 'text' or 'data' parameter");
    }
    //get from config
    if (config.logIp && aText.getPlainText().trim().equals(config.logIpMatchingPattern)) {
      handleIpLogMatch(httpExchange, remoteAddress, parameters);
      //no need to check text again rules
      return;
    }
    textChecker.checkText(aText, httpExchange, parameters, errorRequestLimiter, remoteAddress);
  }

  private void handleIpLogMatch(HttpExchange httpExchange, String remoteAddress, Map<String, String> parameters) {
    Logger logger = LoggerFactory.getLogger(ApiV2.class);
    InetSocketAddress localAddress = httpExchange.getLocalAddress();
    logger.info(String.format("Found log-my-IP text in request from: %s to: %s, requestParams: %s", remoteAddress, localAddress.toString(), parameters));
  }

  private void handleWordsRequest(HttpExchange httpExchange, Map<String, String> params, HTTPServerConfig config) throws Exception {
    ensureGetMethod(httpExchange, "/words");
    UserLimits limits = getUserLimits(params, config);
    DatabaseAccess db = DatabaseAccess.getInstance();
    int offset = params.get("offset") != null ? Integer.parseInt(params.get("offset")) : 0;
    int limit = params.get("limit") != null ? Integer.parseInt(params.get("limit")) : 10;
    logger.info("Started reading dictionary for user: {}, offset: {}, limit: {}, dict_cache: {}, dict: {}",
      limits.getPremiumUid(), offset, limit, limits.getDictCacheSize(), params.get("dict"));

    if (params.containsKey("dict")) {
      throw new IllegalArgumentException("Use parameter 'dicts', not 'dict' in GET /words API method.");
    }

    // optional parameter: groups in comma separated list
    List<String> groups = null;
    if (params.containsKey("dicts")) {
      groups = Arrays.asList(params.get("dicts").split(","));
    } else if (limits.getAccount() != null &&
               limits.getAccount().getDefaultDictionary() != null &&
               !limits.getAccount().getDefaultDictionary().isEmpty()) {
      groups = Collections.singletonList(limits.getAccount().getDefaultDictionary());
    }
    long start = System.nanoTime();
    List<String> words = db.getWords(limits, groups, offset, limit);
    //List<String> words = db.getWords(limits.getPremiumUid(), groups, offset, limit);
    long durationMilliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    logger.info("Finished reading dictionary for user: {}, offset: {}, limit: {}, dict_cache: {}, dict: {}, size: {} in {}ms",
      limits.getPremiumUid(), offset, limit, limits.getDictCacheSize(), params.get("dict"), words.size(), durationMilliseconds);
    writeListResponse("words", words, httpExchange);
  }
  
  private void handleWordAddRequest(HttpExchange httpExchange, Map<String, String> parameters, HTTPServerConfig config) throws Exception {
    ensurePostMethod(httpExchange, "/words/add");
    UserLimits limits = getUserLimits(parameters, config);
    DatabaseAccess db = DatabaseAccess.getInstance();
    String dict = parameters.get("dict");
    if(dict == null &&
       limits.getAccount() != null &&
       limits.getAccount().getDefaultDictionary() != null &&
       !limits.getAccount().getDefaultDictionary().isEmpty()) {
      dict = limits.getAccount().getDefaultDictionary();
    }
    /*
     *  experimental batch mode for adding words,
     *  use mode=batch, words="word1 word2 word3" (whitespace delimited list) instead of word parameter
     */
    if ("batch".equals(parameters.get("mode"))) {
      List<String> words = Arrays.asList(parameters.get("words").split("\\s+"));
      db.addWordBatch(words, limits.getPremiumUid(), dict);
      writeResponse("added", true, httpExchange);
    } else {
      boolean added = db.addWord(parameters.get("word"), limits.getPremiumUid(), dict);
      writeResponse("added", added, httpExchange);
    }
  }

  private void handleWordDeleteRequest(HttpExchange httpExchange, Map<String, String> parameters, HTTPServerConfig config) throws Exception {
    ensurePostMethod(httpExchange, "/words/delete");
    UserLimits limits = getUserLimits(parameters, config);
    DatabaseAccess db = DatabaseAccess.getInstance();
    String dict = parameters.get("dict");
    if(dict == null &&
      limits.getAccount() != null &&
      limits.getAccount().getDefaultDictionary() != null &&
      !limits.getAccount().getDefaultDictionary().isEmpty()) {
      dict = limits.getAccount().getDefaultDictionary();
    }
    boolean deleted;
    if("batch".equals(parameters.get("mode"))) { //Experimental
      List<String> words = Arrays.asList(parameters.get("words").split("\\s+"));
      deleted = db.deleteWordBatch(words, limits.getPremiumUid(),dict);
      writeResponse("deleted", deleted, httpExchange);
    } else {
      deleted = db.deleteWord(parameters.get("word"), limits.getPremiumUid(), dict);
      writeResponse("deleted", deleted, httpExchange);
    }
  }

  private void handleRuleExamplesRequest(HttpExchange httpExchange, Map<String, String> params) throws Exception {
    ensureGetMethod(httpExchange, "/rule/examples");
    if (params.get("lang") == null) {
      throw new BadRequestException("'lang' parameter missing");
    }
    if (params.get("ruleId") == null) {
      throw new BadRequestException("'ruleId' parameter missing");
    }
    Language lang = Languages.getLanguageForShortCode(params.get("lang"));
    JLanguageTool lt = new JLanguageTool(lang);
    if (textChecker.config.languageModelDir != null) {
      lt.activateLanguageModelRules(textChecker.config.languageModelDir);
    }
    List<Rule> rules = lt.getAllRules();
    List<Rule> foundRules = new ArrayList<>();
    for (Rule rule : rules) {
      if (rule.getId().equals(params.get("ruleId"))) {
        foundRules.add(rule);
      }
    }
    if (foundRules.isEmpty()) {
      throw new PathNotFoundException("Rule '" + params.get("ruleId") + "' not found for language " + lang +
              " (LanguageTool version/date: " + JLanguageTool.VERSION + "/" + JLanguageTool.BUILD_DATE + ", total rules of language: " + rules.size() + ")");
    }
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeArrayFieldStart("results");
      g.writeStartObject();
      g.writeStringField("warning", "*** This is not a public API - it may change anytime ***");
      g.writeEndObject();
      for (Rule foundRule : foundRules) {
        for (CorrectExample example : foundRule.getCorrectExamples()) {
          g.writeStartObject();
          g.writeStringField("status", "correct");
          g.writeStringField("sentence", example.getExample());
          g.writeEndObject();
        }
        for (IncorrectExample example : foundRule.getIncorrectExamples()) {
          g.writeStartObject();
          g.writeStringField("status", "incorrect");
          g.writeStringField("sentence", example.getExample());
          g.writeArrayFieldStart("corrections");
          for (String s : example.getCorrections()) {
            g.writeString(s);
          }
          g.writeEndArray();
          g.writeEndObject();
        }
      }
      g.writeEndArray();
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  /*
   * Invalidate cached user information for this user, e.g. after a user has upgraded to premium
   * -> for internal use
   * Authentication avoids the concept of a admin account by requiring credentials for the affected user:
   * -> api keys are available in plain text in database
   */
  private void handleRefreshUserInfoRequest(HttpExchange httpExchange, Map<String, String> params, HTTPServerConfig config) throws Exception {
    ensurePostMethod(httpExchange, "/admin/refreshUser");
    UserLimits limits = getUserLimits(params, config);
    DatabaseAccess db = DatabaseAccess.getInstance();
    if (limits.getPremiumUid() != null) {
      String user = params.get("username");
      if (user != null) {
        db.invalidateUserInfoCache(user);
        writeResponse("success", true, httpExchange);
      } else {
        writeResponse("success", false, httpExchange);
      }
    } else {
      writeResponse("success", false, httpExchange);
    }
  }

  /*
   * Provide information on user that requests this, e.g. for add-on to acquire token + other information
   * Expects user + password via HTTP Basic Auth
   */
  private void handleGetUserInfoRequest(HttpExchange httpExchange, Map<String, String> parameters, HTTPServerConfig config) throws Exception {
    if (httpExchange.getRequestMethod().equalsIgnoreCase("options")) {
      ServerTools.setAllowOrigin(httpExchange, allowOriginUrl);
      httpExchange.getResponseHeaders().put("Access-Control-Allow-Methods", Collections.singletonList("GET, OPTIONS"));
      List<String> requestHeaders = httpExchange.getRequestHeaders().get("Access-Control-Request-Headers");
      if (requestHeaders != null) {
        httpExchange.getResponseHeaders().put("Access-Control-Allow-Headers", Collections.singletonList(String.join(", ", requestHeaders)));
      }
      httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_NO_CONTENT, -1);
      ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_NO_CONTENT);
    } else {
      ensureGetMethod(httpExchange, "/users/me");
      if (!httpExchange.getRequestHeaders().containsKey("Authorization")) {
        throw new AuthException("Expected Basic Authentication");
      }
      String authParameter = parameters.getOrDefault("authMethod", "password");
      if (!(authParameter.equals("password") || 
            authParameter.equals("apiKey") || 
            authParameter.equals("addonToken"))) {
        throw new IllegalArgumentException("Unknown authMethod: " + authParameter);
      }

      String authHeader = httpExchange.getRequestHeaders().getFirst("Authorization");
      BasicAuthentication basicAuthentication = new BasicAuthentication(authHeader);
      String user = basicAuthentication.getUser();
      String password = basicAuthentication.getPassword();
      UserInfoEntry userInfo = null;

      if (authParameter.equals("password")) {
        userInfo = DatabaseAccess.getInstance().getUserInfoWithPassword(user, password);
      } else if (authParameter.equals("addonToken")) {
        userInfo = DatabaseAccess.getInstance().getUserInfoWithAddonToken(user, password);
      } else if (authParameter.equals("apiKey")) {
        userInfo = DatabaseAccess.getInstance().getUserInfoWithApiKey(user, password);
      }

      String format = parameters.getOrDefault("format", "extended");
      if (userInfo != null) {
        if (format.equals("minimal")) {
          StringWriter sw = new StringWriter();
          new ObjectMapper().writeValue(sw, userInfo);
          sendJson(httpExchange, sw);
        } else {
          StringWriter sw = new StringWriter();
          new ObjectMapper().writeValue(sw, DatabaseAccess.getInstance().getExtendedUserInfo(user));
          sendJson(httpExchange, sw);
        }
      } else {
        throw new IllegalStateException("Could not fetch user information");
      }
    }
  }

  private void ensureGetMethod(HttpExchange httpExchange, String url) {
    if (!httpExchange.getRequestMethod().equalsIgnoreCase("get")) {
      throw new BadRequestException(url + " needs to be called with GET");
    }
  }
  
  private void ensurePostMethod(HttpExchange httpExchange, String url) {
    if (!httpExchange.getRequestMethod().equalsIgnoreCase("post")) {
      throw new BadRequestException(url + " needs to be called with POST");
    }
  }

  @NotNull
  private UserLimits getUserLimits(Map<String, String> parameters, HTTPServerConfig config) {
    UserLimits limits = ServerTools.getUserLimits(parameters, config);
    if (limits.getPremiumUid() == null) {
      throw new BadRequestException("This end point needs a user id");
    }
    return limits;
  }

  private void writeResponse(String fieldName, boolean added, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeBooleanField(fieldName, added);
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }
  
  private void writeListResponse(String fieldName, List<String> words, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeArrayFieldStart(fieldName);
      for (String word : words) {
        g.writeString(word);
      }
      g.writeEndArray();
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  private void sendJson(HttpExchange httpExchange, StringWriter sw) throws IOException {
    String response = sw.toString();
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private AnnotatedText getAnnotatedTextFromString(JsonNode data, String text) {
    AnnotatedTextBuilder textBuilder = new AnnotatedTextBuilder().addText(text);
    if (data.has("metaData")) {
      JsonNode metaData = data.get("metaData");
      Iterator<String> it = metaData.fieldNames();
      while (it.hasNext()) {
        String key = it.next();
        String val = metaData.get(key).asText();
        try {
          AnnotatedText.MetaDataKey metaDataKey = AnnotatedText.MetaDataKey.valueOf(key);
          textBuilder.addGlobalMetaData(metaDataKey, val);
        } catch (IllegalArgumentException e) {
          textBuilder.addGlobalMetaData(key, val);
        }
      }
    }
    return textBuilder.build();
  }

  private AnnotatedText getAnnotatedTextFromJson(JsonNode data) {
    AnnotatedTextBuilder atb = new AnnotatedTextBuilder();
    // Expected format:
    // annotation: [
    //   {text: 'text'},
    //   {markup: '<b>'}
    //   {text: 'more text'},
    //   {markup: '</b>'}
    // ]
    //
    for (JsonNode node : data.get("annotation")) {
      if (node.get("text") != null && node.get("markup") != null) {
        throw new BadRequestException("Only either 'text' or 'markup' are supported in an object in 'annotation' list, not both: " + node);
      } else if (node.get("text") != null && node.get("interpretAs") != null) {
        throw new BadRequestException("'text' cannot be used with 'interpretAs' (only 'markup' can): " + node);
      } else if (node.get("text") != null) {
        atb.addText(node.get("text").asText());
      } else if (node.get("markup") != null) {
        if (node.get("interpretAs") != null) {
          atb.addMarkup(node.get("markup").asText(), node.get("interpretAs").asText());
        } else {
          atb.addMarkup(node.get("markup").asText());
        }
      } else {
        throw new BadRequestException("Only 'text' and 'markup' are supported in 'annotation' list: " + node);
      }
    }
    return atb.build();
  }

  String getLanguages() throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartArray();
      List<Language> languages = new ArrayList<>(Languages.get());
      Set<String> longCodes = new HashSet<>();
      for (Language lang : languages) {
        g.writeStartObject();
        g.writeStringField("name", lang.getName());
        g.writeStringField("code", lang.getShortCode());
        g.writeStringField("longCode", lang.getShortCodeWithCountryAndVariant());
        longCodes.add(lang.getShortCodeWithCountryAndVariant());
        g.writeEndObject();
      }
      // add mappings like "fr-FR -> fr" because LibreOffice 7.4 needs them:
      Map<String, Language> codeMap = Languages.getLongCodeToLangMapping();
      for (Map.Entry<String, Language> entry : codeMap.entrySet()) {
        if (!longCodes.contains(entry.getKey())) {
          g.writeStartObject();
          g.writeStringField("name", entry.getValue().getName());
          g.writeStringField("code", entry.getValue().getShortCode());
          g.writeStringField("longCode", entry.getKey());
          g.writeEndObject();
        }
      }
      g.writeEndArray();
    }
    return sw.toString();
  }

  String getSoftwareInfo() throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeObjectFieldStart("software");
      g.writeStringField("name", "LanguageTool");
      g.writeStringField("version", JLanguageTool.VERSION);
      g.writeStringField("buildDate", JLanguageTool.BUILD_DATE);
      g.writeStringField("commit", JLanguageTool.GIT_SHORT_ID);
      g.writeBooleanField("premium", Premium.isPremiumVersion());
      if (Premium.isPremiumVersion()) {
        Premium premium = Premium.get();
        g.writeObjectFieldStart("premiumBuildInfo");
        g.writeStringField("version", premium.getVersion());
        g.writeStringField("buildDate", premium.getBuildDate());
        g.writeStringField("commit", premium.getShortGitId());
        g.writeEndObject();
      }
      g.writeEndObject();
      g.writeEndObject();
    }
    return sw.toString();
  }

  String getConfigurationInfo(Language lang, HTTPServerConfig config) throws IOException {
    StringWriter sw = new StringWriter();
    JLanguageTool lt = new JLanguageTool(lang);
    if (textChecker.config.languageModelDir != null) {
      lt.activateLanguageModelRules(textChecker.config.languageModelDir);
    }
    List<Rule> rules = lt.getAllRules();
    rules = rules.stream().filter(rule -> !Premium.get().isPremiumRule(rule)).collect(Collectors.toList());
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();

//      g.writeObjectFieldStart("software");
//      g.writeStringField("name", "LanguageTool");
//      g.writeStringField("version", JLanguageTool.VERSION);
//      g.writeStringField("buildDate", JLanguageTool.BUILD_DATE);
//      g.writeBooleanField("premium", Premium.isPremiumVersion());
//      g.writeEndObject();
      
      g.writeObjectFieldStart("parameter");
      g.writeNumberField("maxTextLength", config.getMaxTextHardLength());
      g.writeEndObject();

      g.writeArrayFieldStart("rules");
      for (Rule rule : rules) {
        g.writeStartObject();
        g.writeStringField("ruleId", rule.getId());
        g.writeStringField("description", rule.getDescription());
        if (rule.isDictionaryBasedSpellingRule()) {
          g.writeStringField("isDictionaryBasedSpellingRule", "yes");
        }
        if (rule.isDefaultOff()) {
          g.writeStringField("isDefaultOff", "yes");
        }
        if (rule.isOfficeDefaultOff()) {
          g.writeStringField("isOfficeDefaultOff", "yes");
        }
        if (rule.isOfficeDefaultOn()) {
          g.writeStringField("isOfficeDefaultOn", "yes");
        }
        RuleOption[] ruleOptions = rule.getRuleOptions();
        if (ruleOptions != null) {
          //  Compatibility to old version (< 6.5)
          if (ruleOptions[0].getDefaultValue() instanceof Integer) {
            g.writeStringField("hasConfigurableValue", "yes");
            g.writeStringField(RuleOption.CONF_TEXT, ruleOptions[0].getConfigureText());
            g.writeStringField(RuleOption.MAX_CONF_VALUE, Integer.toString((int) ruleOptions[0].getMaxConfigurableValue()));
            g.writeStringField(RuleOption.MIN_CONF_VALUE, Integer.toString((int) ruleOptions[0].getMinConfigurableValue()));
            g.writeStringField(RuleOption.DEFAULT_VALUE, Integer.toString((int)ruleOptions[0].getDefaultValue()));
          }
          //  new Version (>= 6.5)
          g.writeArrayFieldStart("ruleOptions");
          for (RuleOption rOption : ruleOptions) {
            g.writeStartObject();
            Object o = rOption.getDefaultValue();
            if (o instanceof Integer) {
              g.writeStringField(RuleOption.DEFAULT_TYPE, "Integer");
              g.writeNumberField(RuleOption.DEFAULT_VALUE, (int) o);
              g.writeNumberField(RuleOption.MIN_CONF_VALUE, (int) rOption.getMinConfigurableValue());
              g.writeNumberField(RuleOption.MAX_CONF_VALUE, (int) rOption.getMaxConfigurableValue());
            } else if (o instanceof Character) {
              g.writeStringField(RuleOption.DEFAULT_TYPE, "Character");
              g.writeStringField(RuleOption.DEFAULT_VALUE, o.toString());
              g.writeStringField(RuleOption.DEFAULT_VALUE, rOption.getMinConfigurableValue().toString());
              g.writeStringField(RuleOption.DEFAULT_VALUE, rOption.getMaxConfigurableValue().toString());
            } else if (o instanceof Boolean) {
              g.writeStringField(RuleOption.DEFAULT_TYPE, "Boolean");
              g.writeBooleanField(RuleOption.DEFAULT_VALUE, (boolean) o);
              g.writeNumberField(RuleOption.MIN_CONF_VALUE, (int) rOption.getMinConfigurableValue());
              g.writeNumberField(RuleOption.MAX_CONF_VALUE, (int) rOption.getMaxConfigurableValue());
            } else if (o instanceof Float) {
              g.writeStringField(RuleOption.DEFAULT_TYPE, "Float");
              g.writeNumberField(RuleOption.DEFAULT_VALUE, (float) o);
              g.writeNumberField(RuleOption.MIN_CONF_VALUE, (float) rOption.getMinConfigurableValue());
              g.writeNumberField(RuleOption.MAX_CONF_VALUE, (float) rOption.getMaxConfigurableValue());
            } else if (o instanceof Double) {
              g.writeStringField(RuleOption.DEFAULT_TYPE, "Double");
              g.writeNumberField(RuleOption.DEFAULT_VALUE, (double) o);
              g.writeNumberField(RuleOption.MIN_CONF_VALUE, (double) rOption.getMinConfigurableValue());
              g.writeNumberField(RuleOption.MAX_CONF_VALUE, (double) rOption.getMaxConfigurableValue());
            } else {
              g.writeStringField(RuleOption.DEFAULT_TYPE, "String");
              g.writeStringField(RuleOption.DEFAULT_VALUE, o.toString());
              g.writeStringField(RuleOption.MIN_CONF_VALUE, rOption.getMinConfigurableValue().toString());
              g.writeStringField(RuleOption.MAX_CONF_VALUE, rOption.getMaxConfigurableValue().toString());
            }
            g.writeStringField(RuleOption.CONF_TEXT, rOption.getConfigureText());
            g.writeEndObject();
          }
          g.writeEndArray();
        }
        g.writeStringField("categoryId", rule.getCategory().getId().toString());
        g.writeStringField("categoryName", rule.getCategory().getName());
        g.writeStringField("locQualityIssueType", rule.getLocQualityIssueType().toString());
        if (rule instanceof TextLevelRule) {
          g.writeStringField("isTextLevelRule", "yes");
          g.writeStringField("minToCheckParagraph", Integer.toString(((TextLevelRule) rule).minToCheckParagraph()));
        }
        g.writeEndObject();
      }
      g.writeEndArray();

      g.writeEndObject();
    }
    return sw.toString();
  }

}
