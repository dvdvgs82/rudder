/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.domain

import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.archives._
import com.normation.rudder.domain.queries.Query
import com.unboundid.ldap.sdk._
import com.normation.ldap.sdk._
import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.inventory.domain._
import com.normation.rudder.domain.RudderLDAPConstants._
import net.liftweb.common._
import net.liftweb.json._
import com.normation.cfclerk.domain._
import com.normation.rudder.repository.ActiveTechniqueLibraryArchiveId
import com.normation.rudder.repository.NodeGroupLibraryArchiveId
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.api.ApiAccountId
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.appconfig.RudderWebPropertyName
import com.normation.ldap.sdk.syntax._
import com.normation.rudder.rule.category.RuleCategoryId


class CATEGORY(
    val uuid: String,
    val parentDN : DN,
    val name : String = "",
    val description : String = "",
    val isSystem : Boolean = false,
    val objectClass : String,
    val objectClassUuid : String
) extends ENTRY1(objectClassUuid, uuid) {

  lazy val rdn : RDN = this.rdn(this.rdnValue._1)
  lazy val dn = new DN(rdn, parentDN)
  def model() : LDAPEntry = {
    val mod = LDAPEntry(dn)
    mod +=! (A_OC, OC.objectClassNames(objectClass).toSeq:_*)
    mod +=! (A_NAME, name)
    mod +=! (A_DESCRIPTION, description)
    mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)
    mod
  }
}



/**
 *
 * Store the DIT (LDAP tree structure) for Rudder information.
 *
 * That allows mainly :
 * - to access the DIT programmatically, and so to now what is where for
 *   the mains branches ;
 * - to define what object classes are allowed at each level, thanks to
 *   templates (model) of expected entries
 *
 */
class RudderDit(val BASE_DN:DN) extends AbstractDit {
  dit =>
  implicit val DIT = dit

  /**
   * Create a new category for the active technique library
   */
  def activeTechniqueCategory(
      uuid       : String
    , parentDN   : DN
    , name       : String = ""
    , description: String = ""
  ) : CATEGORY = {
    new CATEGORY(
        uuid            = uuid
      , name            = name
      , description     = description
      , parentDN        = parentDN
      , objectClass     = OC_TECHNIQUE_CATEGORY
      , objectClassUuid = A_TECHNIQUE_CATEGORY_UUID
    )
  }

  def groupCategory(
      uuid       : String
    , parentDN   : DN
    , name       : String = ""
    , description: String = ""
  ) : CATEGORY = {
    new CATEGORY(
        uuid            = uuid
      , name            = name
      , description     = description
      , parentDN        = parentDN
      , objectClass = OC_GROUP_CATEGORY
      , objectClassUuid = A_GROUP_CATEGORY_UUID
    )
  }

  def ruleCategory (
      uuid       : String
    , parentDN   : DN
    , name       : String = ""
    , description: String = ""
  ) : CATEGORY = {
    new CATEGORY(
        uuid
      , parentDN
      , name
      , description
      , false
      , OC_RULE_CATEGORY
      , A_RULE_CATEGORY_UUID
    )
  }


  /*
   * Register object here, and force their loading
   */
  dit.register(ACTIVE_TECHNIQUES_LIB.model)
  dit.register(RULES.model)
  dit.register(ARCHIVES.model)
  dit.register(GROUP.model)
  dit.register(GROUP.SYSTEM.model)
  dit.register(API_ACCOUNTS.model)
  dit.register(PARAMETERS.model)
  dit.register(APPCONFIG.model)
  dit.register(RULECATEGORY.model)
  dit.register(NODE_CONFIGS.model)


  //here, we can't use activeTechniqueCategory because we want a subclass
  object ACTIVE_TECHNIQUES_LIB extends CATEGORY(
      uuid = "Active Techniques",
      parentDN = BASE_DN,
      name = "Root of active techniques' library",
      description = "This is the root category for active techniques. It contains subcategories, actives techniques and directives",
      isSystem = true,
      objectClass = OC_TECHNIQUE_CATEGORY,
      objectClassUuid = A_TECHNIQUE_CATEGORY_UUID
  ) {
    activeTechniques =>

    //method that check is an entry if of the given type

    val rootCategoryId = ActiveTechniqueCategoryId(this.uuid)

    def isACategory(e:LDAPEntry) = e.isA(OC_TECHNIQUE_CATEGORY)
    def isAnActiveTechnique(e:LDAPEntry) = e.isA(OC_ACTIVE_TECHNIQUE)
    def isADirective(e:LDAPEntry) = e.isA(OC_DIRECTIVE)

    /**
     * From a DN of a category, return the value of the rdn (uuid)
     */
    def getCategoryIdValue(dn:DN) = singleRdnValue(dn,activeTechniques.rdnAttribute._1)

    /**
     * From a DN of an active technique, return the value of the rdn (uuid)
     */
    def getActiveTechniqueId(dn:DN) : Box[String] = singleRdnValue(dn,A_ACTIVE_TECHNIQUE_UUID)


    def getLDAPRuleID(dn:DN) : Box[String] = singleRdnValue(dn,A_DIRECTIVE_UUID)

    /**
     * Return a new sub category
     */
    def activeTechniqueCategoryModel(uuid:String, parentDN:DN) : LDAPEntry = activeTechniqueCategory(uuid, parentDN).model

    /**
     * Create a new active technique entry
     */
    def activeTechniqueModel(
        uuid:String
      , parentDN:DN
      , techniqueName:TechniqueName
      , acceptationDateTimes:JObject
      , isEnabled:Boolean
      , isSystem : Boolean
    ) : LDAPEntry = {
      val mod = LDAPEntry(new DN(buildRDN(uuid),parentDN))
      mod +=! (A_OC, OC.objectClassNames(OC_ACTIVE_TECHNIQUE).toSeq:_*)
      mod +=! (A_TECHNIQUE_UUID, techniqueName.value)
      mod +=! (A_IS_ENABLED, isEnabled.toLDAPString)
      mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)
      mod +=! (A_ACCEPTATION_DATETIME, compactRender(acceptationDateTimes))
      mod
    }

    def buildRDN(rdn: String): RDN = new RDN(A_ACTIVE_TECHNIQUE_UUID, rdn)
    def buildCategoryRDN(rdn: String): RDN = new RDN(A_TECHNIQUE_CATEGORY_UUID, rdn)

    def directiveModel(uuid:String, techniqueVersion:TechniqueVersion, parentDN:DN) : LDAPEntry = {
      val mod = LDAPEntry(new DN(new RDN(A_DIRECTIVE_UUID,uuid),parentDN))
      mod +=! (A_TECHNIQUE_VERSION, techniqueVersion.toString)
      mod +=! (A_OC, OC.objectClassNames(OC_DIRECTIVE).toSeq:_*)
      mod
    }

  }

  object RULES extends OU("Rules", BASE_DN) {
    rules =>

    def getRuleId(dn:DN) : Box[String] = singleRdnValue(dn,A_RULE_UUID)

    def configRuleDN(uuid:String) = new DN(new RDN(A_RULE_UUID, uuid), rules.dn)

    def ruleModel(
      uuid:String,
      name:String,
      isEnabled : Boolean,
      isSystem : Boolean,
      category : String
    ) : LDAPEntry = {
      val mod = LDAPEntry(configRuleDN(uuid))
      mod +=! (A_OC,OC.objectClassNames(OC_RULE).toSeq:_*)
      mod +=! (A_NAME, name)
      mod +=! (A_IS_ENABLED, isEnabled.toLDAPString)
      mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)
      mod +=! (A_RULE_CATEGORY, category)
      mod
    }

  }


  object RULECATEGORY extends CATEGORY(
      uuid = "rootRuleCategory"
    , parentDN = BASE_DN
    , name = "Rules"
    , description = "This is the main category of Rules"
    , isSystem = true
    , objectClass = OC_RULE_CATEGORY
    , objectClassUuid = A_RULE_CATEGORY_UUID
  ) {
    ruleCategory =>

    val rootCategoryId = RuleCategoryId(this.uuid)

    def isACategory(e:LDAPEntry) = e.isA(OC_RULE_CATEGORY)

    /**
     * From a DN of a category, return the value of the rdn (uuid)
     */
    def getCategoryIdValue(dn:DN) = singleRdnValue(dn,ruleCategory.rdnAttribute._1)

    //def dnFromId(categoryId : RuleCategoryId) : DN = {

    //}


    def ruleCategoryDN(categoryId:String, parentDN:DN) : DN = new DN(new RDN(A_RULE_CATEGORY_UUID,categoryId),parentDN)

    /**
     * Create a new group entry
     */
    def ruleCategoryModel(
        uuid:String
      , parentDN:DN
      , name:String
      , description : String
      , isSystem : Boolean = false
    ) : LDAPEntry = {
      val mod = LDAPEntry(ruleCategory.ruleCategoryDN(uuid, parentDN))
      mod +=! (A_OC, OC.objectClassNames(OC_RULE_CATEGORY).toSeq:_*)
      mod +=! (A_NAME, name)
      mod +=! (A_DESCRIPTION, description)
      mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)

      mod
    }
  }

  object GROUP extends CATEGORY(
      uuid = "GroupRoot",
      parentDN = BASE_DN,
      name = "Root of the group and group categories",
      description = "This is the root category for the groups (both dynamic and static) and group categories",
      isSystem = true,
      objectClass = OC_GROUP_CATEGORY,
      objectClassUuid = A_GROUP_CATEGORY_UUID
  ) {
    group =>

    val rootCategoryId = NodeGroupCategoryId(this.uuid)

    def isACategory(e:LDAPEntry) = e.isA(OC_GROUP_CATEGORY)
    def isAGroup(e:LDAPEntry) = e.isA(OC_RUDDER_NODE_GROUP)
    def isASpecialTarget(e:LDAPEntry) = e.isA(OC_SPECIAL_TARGET)

    /**
     * From a DN of a category, return the value of the rdn (uuid)
     */
    def getCategoryIdValue(dn:DN) = singleRdnValue(dn,group.rdnAttribute._1)

    /**
     * From a DN of a group, return the value of the rdn (uuid)
     */
    def getGroupId(dn:DN) : Box[String] = singleRdnValue(dn,A_NODE_GROUP_UUID)

    /**
     * Return a new sub category
     */
    def groupCategoryModel(uuid:String, parentDN:DN) : LDAPEntry = groupCategory(uuid, parentDN).model

    def groupDN(groupId:String, parentDN:DN) : DN = new DN(new RDN(A_NODE_GROUP_UUID,groupId),parentDN)

    /**
     * Create a new group entry
     */
    def groupModel(uuid:String, parentDN:DN, name:String, description : String, query: Option[Query], isDynamic : Boolean, srvList : Set[NodeId], isEnabled : Boolean, isSystem : Boolean = false) : LDAPEntry = {
      val mod = LDAPEntry(group.groupDN(uuid, parentDN))
      mod +=! (A_OC, OC.objectClassNames(OC_RUDDER_NODE_GROUP).toSeq:_*)
      mod +=! (A_NAME, name)
      mod +=! (A_DESCRIPTION, description)
      mod +=! (A_IS_ENABLED, isEnabled.toLDAPString)
      mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)
      mod +=! (A_IS_DYNAMIC, isDynamic.toLDAPString)
      mod +=! (A_NODE_UUID, srvList.map(x => x.value).toSeq:_*)

      query match {
        case None => // No query to add. Maybe we'd like to enforce that it is not activated
        case Some(q) =>
          mod +=! (A_QUERY_NODE_GROUP, q.toJSONString)
      }
      mod
    }

    /**
     * Group for system targets
     */
    object SYSTEM extends CATEGORY(
      uuid = "SystemGroups",
      parentDN = group.dn,
      name = "Category for system groups and targets",
      description = "This category is the container of all system targets, both groups and other special ones",
      isSystem = true,
      objectClass = OC_GROUP_CATEGORY,
      objectClassUuid = A_GROUP_CATEGORY_UUID
  ) {
      system =>

      def targetDN(target:RuleTarget) : DN = target match {
        case GroupTarget(groupId) => group.groupDN(groupId.value, system.dn)
        case t => new DN(new RDN(A_RULE_TARGET, target.target), system.dn)
      }


    }
  }


  /**
   * That branch contains API authentication principals with their Tokens
   */
  object API_ACCOUNTS extends OU("API Accounts", BASE_DN) {
    principals =>

    /**
     * There is two actual implementations of Rudder server, which
     * differ only slightly in their identification.
     */
    object API_ACCOUNT extends ENTRY1(A_API_UUID) {
      principal =>

      //get id from dn
      def idFromDn(dn:DN) : Option[ApiAccountId] = buildId(dn,principals.dn,{x:String => ApiAccountId(x)})

      //build the dn from an UUID
      def dn(id:ApiAccountId) = new DN(this.rdn(id.value), principals.dn)

    }
  }

  /**
   * That branch contains definition for Rudder server type.
   */
  object NODE_CONFIGS extends ENTRY1("cn", "Nodes Configuration") {
    ou =>

    lazy val rdn : RDN = this.rdn(this.rdnValue._1)
    lazy val dn = new DN(rdn, BASE_DN)

    def model() : LDAPEntry = {
      val mod = LDAPEntry(dn)
      mod +=! (A_OC, OC.objectClassNames(OC_NODES_CONFIG).toSeq:_*)
      mod
    }

  } //end NODE_CONFIGS

  object ARCHIVES extends OU("Archives", BASE_DN) {
    archives =>
    //check for the presence of that entry at bootstrap

    def ruleModel(
      crArchiveId : RuleArchiveId
    ) : LDAPEntry = {
      (new OU("Rules-" + crArchiveId.value, archives.dn)).model
    }

    def userLibDN(id:ActiveTechniqueLibraryArchiveId) : DN = {
      activeTechniqueCategory(
          uuid     = "ActiveTechniqueLibrary-" + id.value
        , parentDN = archives.dn
      ).model.dn
    }

    def groupLibDN(id:NodeGroupLibraryArchiveId) : DN = {
      groupCategory(
          uuid     = "GroupLibrary-" + id.value
        , parentDN = archives.dn
      ).model.dn
    }

    def RuleCategoryLibDN(id:RuleCategoryArchiveId) : DN = {
      ruleCategory(
          uuid     = "RuleCategoryLibrarby-" + id.value
        , parentDN = archives.dn
      ).model.dn
    }

    def parameterModel(
      parameterArchiveId : ParameterArchiveId
    ) : LDAPEntry = {
      (new OU("Parameters-" + parameterArchiveId.value, archives.dn)).model
    }
  }

  object PARAMETERS extends OU("Parameters", BASE_DN) {
    parameters =>

    def getParameter(dn:DN) : Box[String] = singleRdnValue(dn,A_PARAMETER_NAME)

    def parameterDN(parameterName:ParameterName) = new DN(new RDN(A_PARAMETER_NAME, parameterName.value), parameters.dn)

    def parameterModel(
        name        : ParameterName
    ) : LDAPEntry = {
      val mod = LDAPEntry(parameterDN(name))
      mod +=! (A_OC,OC.objectClassNames(OC_PARAMETER).toSeq:_*)
      mod
    }
  }

  /**
   * This is the Rudder APPLICATION configuration.
   * Perhaps it should not be in that DIT, but in an
   * upper one.
   */
  object APPCONFIG extends OU("Application Properties", BASE_DN.getParent) {
    parameters =>

    def getProperty(dn:DN) : Box[String] = singleRdnValue(dn,A_PROPERTY_NAME)

    def propertyDN(parameterName:RudderWebPropertyName) = new DN(new RDN(A_PROPERTY_NAME, parameterName.value), parameters.dn)

    def propertyModel(
        name : RudderWebPropertyName
    ) : LDAPEntry = {
      val mod = LDAPEntry(propertyDN(name))
      mod +=! (A_OC,OC.objectClassNames(OC_PROPERTY).toSeq:_*)
      mod
    }
  }

  private def singleRdnValue(dn:DN, expectedAttributeName:String) : Box[String] = {
      val rdn = dn.getRDN
      if(rdn.isMultiValued) Failure("The given RDN is multivalued, what is not expected for it: %s".format(rdn))
      else if(rdn.hasAttribute(expectedAttributeName)) {
        Full(rdn.getAttributeValues()(0))
      } else {
        Failure("The given DN does not seems to be a valid one for a category. Was expecting RDN attribute name '%s' and got '%s'".format(expectedAttributeName, rdn.getAttributeNames()(0)))
      }
    }

}
