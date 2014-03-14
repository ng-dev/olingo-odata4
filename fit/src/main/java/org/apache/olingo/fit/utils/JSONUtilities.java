/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.olingo.fit.utils;

import static org.apache.olingo.fit.utils.Constants.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.ws.rs.NotFoundException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

public class JSONUtilities extends AbstractUtilities {

  public JSONUtilities(final ODataVersion version) throws Exception {
    super(version);
  }

  @Override
  protected Accept getDefaultFormat() {
    return Accept.JSON_FULLMETA;
  }

  @Override
  protected InputStream addLinks(
          final String entitySetName, final String entitykey, final InputStream is, final Set<String> links)
          throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    final ObjectNode srcNode = (ObjectNode) mapper.readTree(is);
    IOUtils.closeQuietly(is);

    for (String link : links) {
      srcNode.set(link + JSON_NAVIGATION_SUFFIX,
              new TextNode(Commons.getLinksURI(version, entitySetName, entitykey, link)));
    }

    return IOUtils.toInputStream(srcNode.toString());
  }

  @Override
  protected Set<String> retrieveAllLinkNames(InputStream is) throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    final ObjectNode srcNode = (ObjectNode) mapper.readTree(is);
    IOUtils.closeQuietly(is);

    final Set<String> links = new HashSet<String>();

    final Iterator<String> fieldIter = srcNode.fieldNames();

    while (fieldIter.hasNext()) {
      final String field = fieldIter.next();

      if (field.endsWith(JSON_NAVIGATION_BIND_SUFFIX)
              || field.endsWith(JSON_NAVIGATION_SUFFIX)
              || field.endsWith(JSON_MEDIA_SUFFIX)
              || field.endsWith(JSON_EDITLINK_NAME)) {
        if (field.indexOf('@') > 0) {
          links.add(field.substring(0, field.indexOf('@')));
        } else {
          links.add(field);
        }
      }
    }

    return links;
  }

  /**
   * {@inheritDoc }
   */
  @Override
  protected NavigationLinks retrieveNavigationInfo(
          final String entitySetName, final InputStream is)
          throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    final ObjectNode srcNode = (ObjectNode) mapper.readTree(is);
    IOUtils.closeQuietly(is);

    final NavigationLinks links = new NavigationLinks();

    final Iterator<Map.Entry<String, JsonNode>> fieldIter = srcNode.fields();

    while (fieldIter.hasNext()) {
      final Map.Entry<String, JsonNode> field = fieldIter.next();
      if (field.getKey().endsWith(JSON_NAVIGATION_BIND_SUFFIX)) {
        final String title = field.getKey().substring(0, field.getKey().indexOf('@'));
        final List<String> hrefs = new ArrayList<String>();
        if (field.getValue().isArray()) {
          for (JsonNode href : ((ArrayNode) field.getValue())) {
            final String uri = href.asText();
            hrefs.add(uri.substring(uri.lastIndexOf('/') + 1));
          }
        } else {
          final String uri = field.getValue().asText();
          hrefs.add(uri.substring(uri.lastIndexOf('/') + 1));
        }

        links.addLinks(title, hrefs);
      } else if (Commons.linkInfo.get(version).exists(entitySetName, field.getKey())) {
        links.addInlines(field.getKey(), IOUtils.toInputStream(field.getValue().toString()));
      }
    }

    return links;
  }

  /**
   * {@inheritDoc }
   */
  @Override
  protected InputStream normalizeLinks(
          final String entitySetName, final String entityKey, final InputStream is, final NavigationLinks links)
          throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    final ObjectNode srcNode = (ObjectNode) mapper.readTree(is);

    if (links != null) {
      for (String linkTitle : links.getLinkNames()) {
        // normalize link
        srcNode.remove(linkTitle + JSON_NAVIGATION_BIND_SUFFIX);
        srcNode.set(
                linkTitle + JSON_NAVIGATION_SUFFIX,
                new TextNode(String.format("%s(%s)/%s", entitySetName, entityKey, linkTitle)));
      }

      for (String linkTitle : links.getInlineNames()) {
        // normalize link if exist; declare a new one if missing
        srcNode.remove(linkTitle + JSON_NAVIGATION_BIND_SUFFIX);
        srcNode.set(
                linkTitle + JSON_NAVIGATION_SUFFIX,
                new TextNode(String.format("%s(%s)/%s", entitySetName, entityKey, linkTitle)));

        // remove inline
        srcNode.remove(linkTitle);

        // remove from links
        links.removeLink(linkTitle);
      }
    }

    srcNode.set(
            JSON_EDITLINK_NAME,
            new TextNode(Constants.DEFAULT_SERVICE_URL + entitySetName + "(" + entityKey + ")"));

    return IOUtils.toInputStream(srcNode.toString());
  }

  protected static InputStream getJsonPropertyValue(final InputStream src, final String name)
          throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    final JsonNode srcNode = mapper.readTree(src);
    JsonNode node = getJsonProperty(srcNode, new String[] {name}, 0);
    return IOUtils.toInputStream(node.asText());
  }

  public static InputStream getJsonProperty(final InputStream src, final String[] path, final String edmType)
          throws Exception {

    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode srcNode = mapper.readTree(src);

    final ObjectNode property = new ObjectNode(JsonNodeFactory.instance);

    if (StringUtils.isNotBlank(edmType)) {
      property.put(JSON_ODATAMETADATA_NAME, ODATA_METADATA_PREFIX + edmType);
    }

    JsonNode jsonNode = getJsonProperty(srcNode, path, 0);
    if (jsonNode.isObject()) {
      property.putAll((ObjectNode) jsonNode);
    } else {
      property.put("value", jsonNode.asText());
    }

    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    mapper.writeValue(bos, property);

    final InputStream res = new ByteArrayInputStream(bos.toByteArray());
    IOUtils.closeQuietly(bos);

    return res;
  }

  private static JsonNode getJsonProperty(final JsonNode node, final String[] path, final int index)
          throws NotFoundException {
    final Iterator<Map.Entry<String, JsonNode>> iter = node.fields();
    while (iter.hasNext()) {
      final Map.Entry<String, JsonNode> entry = iter.next();
      if (path[index].equals(entry.getKey())) {
        if (path.length - 1 == index) {
          return entry.getValue();
        } else {
          return getJsonProperty(entry.getValue(), path, index + 1);
        }
      }
    }
    throw new NotFoundException();
  }

  public InputStream addJsonInlinecount(
          final InputStream src, final int count, final Accept accept)
          throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode srcNode = mapper.readTree(src);

    ((ObjectNode) srcNode).put(ODATA_COUNT_NAME, count);

    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    mapper.writeValue(bos, srcNode);

    final InputStream res = new ByteArrayInputStream(bos.toByteArray());
    IOUtils.closeQuietly(bos);

    return res;
  }

  public InputStream wrapJsonEntities(final InputStream entities) throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode node = mapper.readTree(entities);

    final ObjectNode res;

    final JsonNode value = node.get(JSON_VALUE_NAME);

    if (value.isArray()) {
      res = mapper.createObjectNode();
      res.set("value", value);
      final JsonNode next = node.get(JSON_NEXTLINK_NAME);
      if (next != null) {
        res.set(JSON_NEXTLINK_NAME, next);
      }
    } else {
      res = (ObjectNode) value;
    }

    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    mapper.writeValue(bos, res);

    final InputStream is = new ByteArrayInputStream(bos.toByteArray());
    IOUtils.closeQuietly(bos);

    return is;
  }

  @Override
  public InputStream selectEntity(final InputStream src, final String[] propertyNames) throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    final ObjectNode srcNode = (ObjectNode) mapper.readTree(src);

    final Set<String> retain = new HashSet<String>();
    retain.add(JSON_ID_NAME);
    retain.add(JSON_TYPE_NAME);
    retain.add(JSON_EDITLINK_NAME);
    retain.add(JSON_NEXTLINK_NAME);
    retain.add(JSON_ODATAMETADATA_NAME);
    retain.add(JSON_VALUE_NAME);

    for (String name : propertyNames) {
      retain.add(name);
      retain.add(name + JSON_NAVIGATION_SUFFIX);
      retain.add(name + JSON_MEDIA_SUFFIX);
      retain.add(name + JSON_TYPE_SUFFIX);
    }

    srcNode.retain(retain);

    return IOUtils.toInputStream(srcNode.toString());
  }

  @Override
  public InputStream readEntities(
          final List<String> links, final String linkName, final String next, final boolean forceFeed)
          throws Exception {

    if (links.isEmpty()) {
      throw new NotFoundException();
    }

    final ObjectMapper mapper = new ObjectMapper();
    final ObjectNode node = mapper.createObjectNode();

    final ByteArrayOutputStream bos = new ByteArrayOutputStream();

    if (forceFeed || links.size() > 1) {
      bos.write("[".getBytes());
    }

    for (String link : links) {
      try {
        final Map.Entry<String, String> uri = Commons.parseEntityURI(link);
        final Map.Entry<String, InputStream> entity =
                readEntity(uri.getKey(), uri.getValue(), Accept.JSON_FULLMETA);

        if (bos.size() > 1) {
          bos.write(",".getBytes());
        }

        IOUtils.copy(entity.getValue(), bos);
      } catch (Exception e) {
        // log and ignore link
        LOG.warn("Error parsing uri {}", link, e);
      }
    }

    if (forceFeed || links.size() > 1) {
      bos.write("]".getBytes());
    }

    node.set(JSON_VALUE_NAME, mapper.readTree(new ByteArrayInputStream(bos.toByteArray())));

    if (StringUtils.isNotBlank(next)) {
      node.set(JSON_NEXTLINK_NAME, new TextNode(next));
    }

    return IOUtils.toInputStream(node.toString());
  }

  @Override
  protected InputStream replaceLink(
          final InputStream toBeChanged, final String linkName, final InputStream replacement)
          throws Exception {
    final ObjectMapper mapper = new ObjectMapper();

    final ObjectNode toBeChangedNode = (ObjectNode) mapper.readTree(toBeChanged);
    final ObjectNode replacementNode = (ObjectNode) mapper.readTree(replacement);

    if (toBeChangedNode.get(linkName + JSON_NAVIGATION_SUFFIX) == null) {
      throw new NotFoundException();
    }

    toBeChangedNode.set(linkName, replacementNode.get(JSON_VALUE_NAME));

    final JsonNode next = replacementNode.get(linkName + JSON_NEXTLINK_NAME);
    if (next != null) {
      toBeChangedNode.set(linkName + JSON_NEXTLINK_SUFFIX, next);
    }

    return IOUtils.toInputStream(toBeChangedNode.toString());
  }

  @Override
  protected Map<String, InputStream> getChanges(final InputStream src) throws Exception {
    final Map<String, InputStream> res = new HashMap<String, InputStream>();

    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode srcObject = mapper.readTree(src);

    final Iterator<Map.Entry<String, JsonNode>> fields = srcObject.fields();
    while (fields.hasNext()) {
      final Map.Entry<String, JsonNode> field = fields.next();
      res.put(field.getKey(), IOUtils.toInputStream(field.getValue().toString()));
    }

    return res;
  }

  @Override
  protected InputStream setChanges(
          final InputStream toBeChanged, final Map<String, InputStream> properties) throws Exception {

    final ObjectMapper mapper = new ObjectMapper();
    final ObjectNode toBeChangedObject = (ObjectNode) mapper.readTree(toBeChanged);

    for (Map.Entry<String, InputStream> property : properties.entrySet()) {
      final JsonNode propertyNode = mapper.readTree(property.getValue());
      toBeChangedObject.set(property.getKey(), propertyNode);
    }

    return IOUtils.toInputStream(toBeChangedObject.toString());
  }

  public static Map.Entry<String, List<String>> extractLinkURIs(final InputStream is)
          throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    final ObjectNode srcNode = (ObjectNode) mapper.readTree(is);
    IOUtils.closeQuietly(is);

    final List<String> links = new ArrayList<String>();

    JsonNode uris = srcNode.get("value");
    if (uris == null) {
      final JsonNode url = srcNode.get("url");
      if (url != null) {
        links.add(url.textValue());
      }
    } else {
      final Iterator<JsonNode> iter = ((ArrayNode) uris).iterator();
      while (iter.hasNext()) {
        links.add(iter.next().get("url").textValue());
      }
    }

    final JsonNode next = srcNode.get(JSON_NEXTLINK_NAME);

    return new SimpleEntry<String, List<String>>(next == null ? null : next.asText(), links);
  }
}