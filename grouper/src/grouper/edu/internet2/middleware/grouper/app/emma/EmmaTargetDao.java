package edu.internet2.middleware.grouper.app.emma;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioner;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningEntity;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningGroup;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningMembership;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningObjectChange;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.GrouperProvisionerDaoCapabilities;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.GrouperProvisionerTargetDaoBase;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteEntityRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteEntityResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteGroupRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteGroupResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteMembershipRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoDeleteMembershipResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertEntityRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertEntityResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertGroupRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertGroupResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertMembershipRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoInsertMembershipResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveAllEntitiesRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveAllEntitiesResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveAllGroupsRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveAllGroupsResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveEntityRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveEntityResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveGroupRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveGroupResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveGroupsRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveGroupsResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveMembershipsByGroupRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoRetrieveMembershipsByGroupResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoTimingInfo;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoUpdateEntityRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoUpdateEntityResponse;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoUpdateGroupRequest;
import edu.internet2.middleware.grouper.app.provisioning.targetDao.TargetDaoUpdateGroupResponse;
import edu.internet2.middleware.grouper.util.GrouperHttpClient;
import edu.internet2.middleware.grouper.util.GrouperHttpClientLog;
import edu.internet2.middleware.grouper.util.GrouperUtil;


public class EmmaTargetDao extends GrouperProvisionerTargetDaoBase {

  @Override
  public boolean loggingStart() {
    return GrouperHttpClient.logStart(new GrouperHttpClientLog());
  }

  @Override
  public String loggingStop() {
    return GrouperHttpClient.logEnd();
  }

  private String configId() {
    EmmaConfiguration emmaConfiguration = (EmmaConfiguration) this.getGrouperProvisioner()
        .retrieveGrouperProvisioningConfiguration();
    return emmaConfiguration.getEmmaExternalSystemConfigId();
  }

  // ==================== Groups ====================

  @Override
  public TargetDaoRetrieveAllGroupsResponse retrieveAllGroups(TargetDaoRetrieveAllGroupsRequest targetDaoRetrieveAllGroupsRequest) {
    List<ProvisioningGroup> results = new ArrayList<ProvisioningGroup>();
    long startNanos = System.nanoTime();

    try {
      List<EmmaGroup> emmaGroups = EmmaApiCommands.retrieveGroups(configId());
      for (EmmaGroup emmaGroup : emmaGroups) {
        results.add(emmaGroup.toProvisioningGroup());
      }
      return new TargetDaoRetrieveAllGroupsResponse(results);
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveAllGroups", startNanos));
    }
  }

  @Override
  public TargetDaoRetrieveGroupResponse retrieveGroup(TargetDaoRetrieveGroupRequest targetDaoRetrieveGroupRequest) {
    long startNanos = System.nanoTime();

    try {
      String configId = configId();

      if (StringUtils.equals("id", targetDaoRetrieveGroupRequest.getSearchAttribute())) {
        EmmaGroup emmaGroup = EmmaApiCommands.retrieveGroup(configId,
            GrouperUtil.longValue(targetDaoRetrieveGroupRequest.getSearchAttributeValue()));
        ProvisioningGroup targetGroup = emmaGroup == null ? null : emmaGroup.toProvisioningGroup();
        return new TargetDaoRetrieveGroupResponse(targetGroup);
      } else if (StringUtils.equals("name", targetDaoRetrieveGroupRequest.getSearchAttribute())) {
        // Emma has no lookup-group-by-name endpoint, so scan the full list
        List<EmmaGroup> emmaGroups = EmmaApiCommands.retrieveGroups(configId);
        for (EmmaGroup emmaGroup : emmaGroups) {
          if (StringUtils.equals(emmaGroup.getName(),
              GrouperUtil.stringValue(targetDaoRetrieveGroupRequest.getSearchAttributeValue()))) {
            return new TargetDaoRetrieveGroupResponse(emmaGroup.toProvisioningGroup());
          }
        }
      } else {
        throw new RuntimeException("id or name is required as a group search attribute");
      }
      return new TargetDaoRetrieveGroupResponse();
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveGroup", startNanos));
    }
  }

  @Override
  public TargetDaoInsertGroupResponse insertGroup(TargetDaoInsertGroupRequest targetDaoInsertGroupRequest) {
    long startNanos = System.nanoTime();
    ProvisioningGroup targetGroup = targetDaoInsertGroupRequest.getTargetGroup();

    try {
      EmmaGroup emmaGroup = EmmaGroup.fromProvisioningGroup(targetGroup, null);
      EmmaGroup createdGroup = EmmaApiCommands.createGroup(configId(), emmaGroup);

      targetGroup.setId(String.valueOf(createdGroup.getId()));
      targetGroup.setProvisioned(true);

      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetGroup.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(true);
      }

      return new TargetDaoInsertGroupResponse();
    } catch (Exception e) {
      targetGroup.setProvisioned(false);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetGroup.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(false);
      }
      throw e;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("insertGroup", startNanos));
    }
  }

  @Override
  public TargetDaoUpdateGroupResponse updateGroup(TargetDaoUpdateGroupRequest targetDaoUpdateGroupRequest) {
    long startNanos = System.nanoTime();
    ProvisioningGroup targetGroup = targetDaoUpdateGroupRequest.getTargetGroup();

    try {
      // Emma groups only have a name; PUT sets the group_name
      EmmaGroup emmaGroup = EmmaGroup.fromProvisioningGroup(targetGroup, null);
      EmmaApiCommands.updateGroup(configId(), emmaGroup);

      targetGroup.setProvisioned(true);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetGroup.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(true);
      }

      return new TargetDaoUpdateGroupResponse();
    } catch (Exception e) {
      targetGroup.setProvisioned(false);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetGroup.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(false);
      }
      throw e;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("updateGroup", startNanos));
    }
  }

  @Override
  public TargetDaoDeleteGroupResponse deleteGroup(TargetDaoDeleteGroupRequest targetDaoDeleteGroupRequest) {
    long startNanos = System.nanoTime();
    ProvisioningGroup targetGroup = targetDaoDeleteGroupRequest.getTargetGroup();

    try {
      EmmaGroup emmaGroup = EmmaGroup.fromProvisioningGroup(targetGroup, null);
      EmmaApiCommands.deleteGroup(configId(), emmaGroup.getId());

      targetGroup.setProvisioned(true);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetGroup.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(true);
      }

      return new TargetDaoDeleteGroupResponse();
    } catch (Exception e) {
      targetGroup.setProvisioned(false);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetGroup.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(false);
      }
      throw e;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("deleteGroup", startNanos));
    }
  }

  // ==================== Entities (members) ====================

  @Override
  public TargetDaoRetrieveAllEntitiesResponse retrieveAllEntities(TargetDaoRetrieveAllEntitiesRequest targetDaoRetrieveAllEntitiesRequest) {
    List<ProvisioningEntity> results = new ArrayList<ProvisioningEntity>();
    long startNanos = System.nanoTime();

    try {
      List<EmmaMember> members = EmmaApiCommands.retrieveMembers(configId());
      for (EmmaMember member : members) {
        results.add(member.toProvisioningEntity());
      }
      return new TargetDaoRetrieveAllEntitiesResponse(results);
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveAllEntities", startNanos));
    }
  }

  @Override
  public TargetDaoRetrieveEntityResponse retrieveEntity(TargetDaoRetrieveEntityRequest targetDaoRetrieveEntityRequest) {
    long startNanos = System.nanoTime();

    try {
      EmmaMember member = EmmaApiCommands.retrieveMemberByAttribute(configId(),
          targetDaoRetrieveEntityRequest.getSearchAttribute(),
          targetDaoRetrieveEntityRequest.getSearchAttributeValue());

      ProvisioningEntity targetEntity = member == null ? null : member.toProvisioningEntity();

      TargetDaoRetrieveEntityResponse targetDaoRetrieveEntityResponse = new TargetDaoRetrieveEntityResponse(targetEntity);
      if (targetDaoRetrieveEntityRequest.isIncludeNativeEntity()) {
        targetDaoRetrieveEntityResponse.setTargetNativeEntity(member);
      }
      return targetDaoRetrieveEntityResponse;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveEntity", startNanos));
    }
  }

  @Override
  public TargetDaoInsertEntityResponse insertEntity(TargetDaoInsertEntityRequest targetDaoInsertEntityRequest) {
    long startNanos = System.nanoTime();
    ProvisioningEntity targetEntity = targetDaoInsertEntityRequest.getTargetEntity();

    try {
      EmmaMember emmaMember = EmmaMember.fromProvisioningEntity(targetEntity, null);

      // Emma's /members/add both creates and updates (keyed on email) and returns the member_id
      EmmaMember createdMember = EmmaApiCommands.addMember(configId(), emmaMember);

      targetEntity.setId(String.valueOf(createdMember.getId()));
      targetEntity.setProvisioned(true);

      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetEntity.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(true);
      }

      return new TargetDaoInsertEntityResponse();
    } catch (Exception e) {
      targetEntity.setProvisioned(false);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetEntity.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(false);
      }
      throw e;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("insertEntity", startNanos));
    }
  }

  @Override
  public TargetDaoUpdateEntityResponse updateEntity(TargetDaoUpdateEntityRequest targetDaoUpdateEntityRequest) {
    long startNanos = System.nanoTime();
    ProvisioningEntity targetEntity = targetDaoUpdateEntityRequest.getTargetEntity();

    try {
      // collect the field names that need to be updated from the provisioning object changes
      Set<String> fieldNamesToUpdate = new HashSet<String>();
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetEntity.getInternal_objectChanges())) {
        fieldNamesToUpdate.add(provisioningObjectChange.getAttributeName());
      }

      EmmaMember emmaMember = EmmaMember.fromProvisioningEntity(targetEntity, null);
      EmmaApiCommands.updateMember(configId(), emmaMember, fieldNamesToUpdate);

      targetEntity.setProvisioned(true);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetEntity.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(true);
      }

      return new TargetDaoUpdateEntityResponse();
    } catch (Exception e) {
      targetEntity.setProvisioned(false);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetEntity.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(false);
      }
      throw e;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("updateEntity", startNanos));
    }
  }

  @Override
  public TargetDaoDeleteEntityResponse deleteEntity(TargetDaoDeleteEntityRequest targetDaoDeleteEntityRequest) {
    long startNanos = System.nanoTime();
    ProvisioningEntity targetEntity = targetDaoDeleteEntityRequest.getTargetEntity();

    try {
      EmmaMember emmaMember = EmmaMember.fromProvisioningEntity(targetEntity, null);
      EmmaApiCommands.deleteMember(configId(), emmaMember.getId());

      targetEntity.setProvisioned(true);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetEntity.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(true);
      }

      return new TargetDaoDeleteEntityResponse();
    } catch (Exception e) {
      targetEntity.setProvisioned(false);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetEntity.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(false);
      }
      throw e;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("deleteEntity", startNanos));
    }
  }

  // ==================== Memberships ====================

  @Override
  public TargetDaoInsertMembershipResponse insertMembership(TargetDaoInsertMembershipRequest targetDaoInsertMembershipRequest) {
    long startNanos = System.nanoTime();
    ProvisioningMembership targetMembership = targetDaoInsertMembershipRequest.getTargetMembership();

    try {
      EmmaApiCommands.addGroupMembership(configId(),
          GrouperUtil.longValue(targetMembership.getProvisioningGroupId()),
          GrouperUtil.longValue(targetMembership.getProvisioningEntityId()));

      targetMembership.setProvisioned(true);
      return new TargetDaoInsertMembershipResponse();
    } catch (Exception e) {
      targetMembership.setProvisioned(false);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetMembership.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(false);
      }
      throw e;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("insertMembership", startNanos));
    }
  }

  @Override
  public TargetDaoDeleteMembershipResponse deleteMembership(TargetDaoDeleteMembershipRequest targetDaoDeleteMembershipRequest) {
    long startNanos = System.nanoTime();
    ProvisioningMembership targetMembership = targetDaoDeleteMembershipRequest.getTargetMembership();

    try {
      EmmaApiCommands.removeGroupMembership(configId(),
          GrouperUtil.longValue(targetMembership.getProvisioningGroupId()),
          GrouperUtil.longValue(targetMembership.getProvisioningEntityId()));

      targetMembership.setProvisioned(true);
      return new TargetDaoDeleteMembershipResponse();
    } catch (Exception e) {
      targetMembership.setProvisioned(false);
      for (ProvisioningObjectChange provisioningObjectChange : GrouperUtil.nonNull(targetMembership.getInternal_objectChanges())) {
        provisioningObjectChange.setProvisioned(false);
      }
      throw e;
    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("deleteMembership", startNanos));
    }
  }

  @Override
  public TargetDaoRetrieveMembershipsByGroupResponse retrieveMembershipsByGroup(TargetDaoRetrieveMembershipsByGroupRequest targetDaoRetrieveMembershipsByGroupRequest) {
    long startNanos = System.nanoTime();
    ProvisioningGroup targetGroup = targetDaoRetrieveMembershipsByGroupRequest.getTargetGroup();

    String targetGroupId = resolveTargetGroupId(targetGroup, this.getGrouperProvisioner());
    List<ProvisioningMembership> provisioningMemberships = new ArrayList<ProvisioningMembership>();

    if (StringUtils.isBlank(targetGroupId)) {
      return new TargetDaoRetrieveMembershipsByGroupResponse(provisioningMemberships);
    }

    try {
      List<EmmaMember> members = EmmaApiCommands.retrieveMembershipsByGroup(configId(),
          GrouperUtil.longValue(targetGroupId));

      for (EmmaMember member : members) {
        ProvisioningMembership targetMembership = new ProvisioningMembership();
        targetMembership.setProvisioningGroupId(targetGroupId);
        targetMembership.setProvisioningEntityId(member.getId() == null ? null : Long.toString(member.getId()));
        provisioningMemberships.add(targetMembership);
      }

      return new TargetDaoRetrieveMembershipsByGroupResponse(provisioningMemberships);

    } finally {
      this.addTargetDaoTimingInfo(new TargetDaoTimingInfo("retrieveMembershipsByGroup", startNanos));
    }
  }

  public String resolveTargetGroupId(ProvisioningGroup targetGroup, GrouperProvisioner grouperProvisioner) {
    if (targetGroup == null) {
      return null;
    }

    if (StringUtils.isNotBlank(targetGroup.getId())) {
      return targetGroup.getId();
    }

    TargetDaoRetrieveGroupsRequest targetDaoRetrieveGroupsRequest = new TargetDaoRetrieveGroupsRequest();
    targetDaoRetrieveGroupsRequest.setTargetGroups(GrouperUtil.toList(targetGroup));
    targetDaoRetrieveGroupsRequest.setIncludeAllMembershipsIfApplicable(false);
    TargetDaoRetrieveGroupsResponse targetDaoRetrieveGroupsResponse = grouperProvisioner.retrieveGrouperProvisioningTargetDaoAdapter().retrieveGroups(
        targetDaoRetrieveGroupsRequest);

    if (targetDaoRetrieveGroupsResponse == null || GrouperUtil.length(targetDaoRetrieveGroupsResponse.getTargetGroups()) == 0) {
      return null;
    }

    return targetDaoRetrieveGroupsResponse.getTargetGroups().get(0).getId();
  }

  @Override
  public void registerGrouperProvisionerDaoCapabilities(
      GrouperProvisionerDaoCapabilities grouperProvisionerDaoCapabilities) {
    grouperProvisionerDaoCapabilities.setCanRetrieveAllEntities(true);
    grouperProvisionerDaoCapabilities.setCanRetrieveEntity(true);
    grouperProvisionerDaoCapabilities.setCanInsertEntity(true);
    grouperProvisionerDaoCapabilities.setCanUpdateEntity(true);
    grouperProvisionerDaoCapabilities.setCanDeleteEntity(true);
    grouperProvisionerDaoCapabilities.setCanRetrieveAllGroups(true);
    grouperProvisionerDaoCapabilities.setCanRetrieveGroup(true);
    grouperProvisionerDaoCapabilities.setCanInsertGroup(true);
    grouperProvisionerDaoCapabilities.setCanUpdateGroup(true);
    grouperProvisionerDaoCapabilities.setCanDeleteGroup(true);
    grouperProvisionerDaoCapabilities.setCanInsertMembership(true);
    grouperProvisionerDaoCapabilities.setCanDeleteMembership(true);
    grouperProvisionerDaoCapabilities.setCanRetrieveMembershipsAllByGroup(true);
  }

}
