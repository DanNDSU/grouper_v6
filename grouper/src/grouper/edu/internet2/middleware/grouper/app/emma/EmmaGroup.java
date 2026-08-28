package edu.internet2.middleware.grouper.app.emma;

import java.sql.Types;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.internet2.middleware.grouper.app.provisioning.ProvisioningGroup;
import edu.internet2.middleware.grouper.ddl.DdlVersionBean;
import edu.internet2.middleware.grouper.ddl.GrouperDdlUtils;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Database;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Table;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;
import edu.internet2.middleware.grouperClient.util.GrouperClientUtils;

/**
 * Represents an Emma member group.
 *
 * Emma groups have a numeric member_group_id and a group_name. Unlike some
 * targets, Emma groups have no description field, so only id and name are modeled.
 */
public class EmmaGroup {

  public static void createTableEmmaGroup(DdlVersionBean ddlVersionBean, Database database) {

    final String groupTableName = "mock_emma_group";

    try {
      new GcDbAccess().sql("select count(*) from " + groupTableName).select(int.class);
    } catch (Exception e) {

      Table groupTable = GrouperDdlUtils.ddlutilsFindOrCreateTable(database, groupTableName);

      GrouperDdlUtils.ddlutilsFindOrCreateColumn(groupTable, "group_name", Types.VARCHAR, "256", false, true);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(groupTable, "id", Types.BIGINT, "20", true, true);

      GrouperDdlUtils.ddlutilsFindOrCreateIndex(database, groupTableName, "mock_emma_group_name_idx", true, "group_name");
    }

  }

  /**
   * convert from jackson json
   * @param groupNode
   * @return the group
   */
  public static EmmaGroup fromJson(JsonNode groupNode) {
    if (groupNode == null) {
      return null;
    }

    EmmaGroup emmaGroup = new EmmaGroup();
    emmaGroup.name = GrouperUtil.jsonJacksonGetString(groupNode, "group_name");
    emmaGroup.id = GrouperUtil.jsonJacksonGetLong(groupNode, "member_group_id");

    return emmaGroup;
  }

  /**
   * convert to jackson json
   * @param fieldNamesToSet the field names we'll be setting
   * @return a jackson ObjectNode representing the EmmaGroup object
   */
  public ObjectNode toJson(Set<String> fieldNamesToSet) {
    ObjectNode result = GrouperUtil.jsonJacksonNode();

    if (fieldNamesToSet == null || fieldNamesToSet.contains("name")) {
      result.put("group_name", this.name);
    }
    return result;
  }

  /**
   * Convert to a provisioning group
   * @return a Provisioning Group object
   */
  public ProvisioningGroup toProvisioningGroup() {
    ProvisioningGroup targetGroup = new ProvisioningGroup();
    if (this.id != null) {
      targetGroup.setId(Long.toString(this.id));
    }
    targetGroup.assignAttributeValue("name", this.name);
    return targetGroup;
  }

  /**
   * Convert a provisioning group to an EmmaGroup
   * @param targetGroup the provisioning group
   * @param fieldNamesToSet the field names in EmmaGroup to set
   * @return the converted EmmaGroup
   */
  public static EmmaGroup fromProvisioningGroup(ProvisioningGroup targetGroup, Set<String> fieldNamesToSet) {
    EmmaGroup emmaGroup = new EmmaGroup();

    if (fieldNamesToSet == null || fieldNamesToSet.contains("id")) {
      if (targetGroup.getId() == null) {
        emmaGroup.setId(null);
      } else {
        emmaGroup.setId(Long.parseLong(targetGroup.getId()));
      }
    }

    if (fieldNamesToSet == null || fieldNamesToSet.contains("name")) {
      emmaGroup.setName(targetGroup.retrieveAttributeValueString("name"));
    }

    return emmaGroup;
  }


  @Override
  public String toString() {
    return GrouperClientUtils.toStringReflection(this);
  }

  private Long id;
  private String name;


  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

}
