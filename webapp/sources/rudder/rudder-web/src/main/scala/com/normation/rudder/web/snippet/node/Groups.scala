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

package com.normation.rudder.web.snippet.node

import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.web.components.{
  NodeGroupForm,NodeGroupCategoryForm
}
import com.normation.rudder.web.model._
import net.liftweb.http.LocalSnippet
import com.normation.rudder.repository._
import com.normation.rudder.web.components.popup.CreateCategoryOrGroupPopup
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.json._
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.utils.HashcodeCaching
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.plugins.SnippetExtensionKey
import com.normation.plugins.SpringExtendableSnippet
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.web.services.DisplayNodeGroupTree
import com.normation.rudder.authorization.Edit

object Groups {
  val htmlId_groupTree = "groupTree"
  val htmlId_item = "ajaxItemContainer"
  val htmlId_updateContainerForm = "updateContainerForm"

  private sealed trait RightPanel
  private case object NoPanel extends RightPanel
  private case class GroupForm(group:NodeGroup, parentCategoryId:NodeGroupCategoryId) extends RightPanel with HashcodeCaching
  private case class CategoryForm(category: NodeGroupCategory) extends RightPanel with HashcodeCaching

}

class Groups extends StatefulSnippet with SpringExtendableSnippet[Groups] with Loggable {
  import Groups._

  private[this] val getFullGroupLibrary   = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _
  private[this] val woNodeGroupRepository = RudderConfig.woNodeGroupRepository
  private[this] val uuidGen               = RudderConfig.stringUuidGenerator

  private[this] var boxGroupLib = getFullGroupLibrary()

  val mainDispatch = {
    RudderConfig.configService.rudder_workflow_enabled match {
      case Full(workflowEnabled) =>
        Map(
            "head" -> head _
          , "detailsPopup" ->   { _ : NodeSeq =>  NodeGroupForm.staticBody }
          , "initRightPanel" -> { _ : NodeSeq => initRightPanel(workflowEnabled) }
          , "groupHierarchy" -> groupHierarchy(boxGroupLib, workflowEnabled)
        )
      case eb: EmptyBox =>
        val e = eb ?~! "Error when getting Rudder application configuration for workflow activation"
        logger.error(s"Error when displaying groups : ${e.messageChain}")
        Map(
            "head" -> { _:NodeSeq => NodeSeq.Empty }
          , "detailsPopup" ->  { _:NodeSeq => NodeSeq.Empty }
          , "initRightPanel" -> { _: NodeSeq => NodeSeq.Empty  }
          , "groupHierarchy" -> { _: NodeSeq => <div class="error">{e.msg}</div> }
        )
    }

  }

  def extendsAt = SnippetExtensionKey(classOf[Groups].getSimpleName)

  //the current nodeGroupCategoryForm component
  private[this] val nodeGroupCategoryForm = new LocalSnippet[NodeGroupCategoryForm]

  //the current nodeGroupForm component
  private[this] val nodeGroupForm = new LocalSnippet[NodeGroupForm]

  //the popup component
  private[this] val creationPopup = new LocalSnippet[CreateCategoryOrGroupPopup]

  private[this] var selectedCategoryId = boxGroupLib.map(_.id)

  /**
   * Head of the portlet, nothing much yet
   * @param html
   * @return
   */
  def head(html:NodeSeq) : NodeSeq = {
    NodeGroupForm.staticInit
   }

  /**
   * Display the Groups hierarchy fieldset, with the JS tree
   * @param html
   * @return
   */
  def groupHierarchy(rootCategory: Box[FullNodeGroupCategory], workflowEnabled: Boolean) : CssSel = (
      "#groupTree" #> buildGroupTree("", workflowEnabled)
    & "#newItem"   #> groupNewItem(workflowEnabled)
  )

  def groupNewItem(workflowEnabled: Boolean) : NodeSeq = {
      SHtml.ajaxButton("Create", () => showPopup(workflowEnabled), ("class","btn btn-success new-icon pull-right"))
  }

  /**
   * Does the init part (showing the right component and highlighting
   * the tree if necessary)
   */
  def initRightPanel(workflowEnabled: Boolean) : NodeSeq = {
    Script(OnLoad(parseJsArg(workflowEnabled)(boxGroupLib)))
  }

  /**
   * If a query is passed as argument, try to dejoniffy-it, in a best effort
   * way - just don't take of errors.
   *
   * We want to look for #{ "groupId":"XXXXXXXXXXXX" }
   */
  private[this] def parseJsArg(workflowEnabled: Boolean)(rootCategory: Box[FullNodeGroupCategory]) : JsCmd = {
    def displayDetails(groupId:String) = {
      val gid = NodeGroupId(groupId)
      rootCategory match {
        case eb: EmptyBox => Noop
        case Full(lib) => lib.allGroups.get(gid) match {
          case None => Noop
          case Some(fullGroupTarget) => //so we also have its parent category
            //no modification, so no refreshGroupLib
            refreshTree(htmlTreeNodeId(groupId), workflowEnabled) &
            showGroupSection(workflowEnabled) (fullGroupTarget.nodeGroup, lib.categoryByGroupId(gid)) &
            JsRaw("createTooltip()")
        }
      }
    }

    JsRaw("""
        var groupId = null;
        try {
          groupId = JSON.parse(decodeURI(window.location.hash.substring(1))).groupId ;
        } catch(e) {
          groupId = null
        }
        if( groupId != null && groupId.length > 0) {
          %s;
        }
    """.format(SHtml.ajaxCall(JsVar("groupId"), displayDetails _ )._2.toJsCmd)
    )
  }

  ////////////////////////////////////////////////////////////////////////////////////

  private[this] def htmlTreeNodeId(id:String) = "jsTree-" + id

  /**
   *  Manage the state of what should be displayed on the right panel.
   * It could be nothing, a group edit form, or a category edit form.
   */
  private[this] def setAndShowRightPanel(panel: RightPanel, rootCategory: FullNodeGroupCategory, workflowEnabled: Boolean) : NodeSeq = {
    panel match {
      case NoPanel => NodeSeq.Empty
      case GroupForm(group,parentCatId) =>
        val form = new NodeGroupForm(
            htmlId_item
          , group
          , parentCatId
          , rootCategory
          , workflowEnabled
          , { (redirect: Either[(NodeGroup,NodeGroupCategoryId),ChangeRequestId]) =>
              redirect match {
                case Left((newGroup,newParentId)) =>
                  refreshGroupLib()
                  val newPanel = GroupForm(newGroup, newParentId)
                  refreshTree(htmlTreeNodeId(newGroup.id.value), workflowEnabled) &
                  refreshRightPanel(newPanel , workflowEnabled)
                case Right(crId) =>
                  JsInitContextLinkUtil.redirectToChangeRequestLink(crId)
              }
            }
        )

        nodeGroupForm.set(Full(form))
        form.dispatch("showForm")(NodeSeq.Empty);

      case CategoryForm(category) =>
        val form = new NodeGroupCategoryForm(htmlId_item, category, rootCategory, onSuccessCallback(workflowEnabled))
        nodeGroupCategoryForm.set(Full(form))
        form.showForm()
    }
  }

  //utility to refresh right panel
  private[this] def refreshRightPanel(panel:RightPanel, workflowEnabled: Boolean) : JsCmd = {
    boxGroupLib match {
      case Full(lib) => SetHtml(htmlId_item, setAndShowRightPanel(panel, lib, workflowEnabled))
      case eb: EmptyBox =>
        val e = eb ?~! "Error when trying to get the root node group category"
        logger.error(e.messageChain)
        Alert(e.messageChain)
    }
  }

  //that must be separated from refreshTree/refreshRightPanel
  //to avoid duplicate refresh or useless one (when only displaying without modification)
  private[this] def refreshGroupLib() : Unit = {
    boxGroupLib = getFullGroupLibrary()
  }

  private[this] def setCreationPopup(rootCategory: FullNodeGroupCategory, workflowEnabled: Boolean) : Unit = {
    creationPopup.set(Full(new CreateCategoryOrGroupPopup(
        None
      , rootCategory
      , selectedCategoryId
      , onSuccessCategory = displayCategory(workflowEnabled)
      , onSuccessGroup = showGroupSection(workflowEnabled)
      , onSuccessCallback = onSuccessCallback(workflowEnabled)
    )))
  }

  private[this] def onSuccessCallback(workflowEnabled: Boolean) = {
    (id: String) => {refreshGroupLib; refreshTree(htmlTreeNodeId(id), workflowEnabled) }
  }

  /**
   * build the tree of categories and group and init its JS
   */
  private[this] def buildGroupTree(selectedNode: String, workflowEnabled: Boolean) : NodeSeq = {
    boxGroupLib match {
      case eb: EmptyBox =>
        val e = eb ?~! "Can not get the group library"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex =>
          logger.error("Root exception was:", ex)
        }

        <div id={htmlId_groupTree}>Error: {e.msg}</div>
      case Full(lib) =>
      //We want to fold categories if there are more than 11 (10 + 1 taking into account the hidden root node)
      val foldCategories = lib.allCategories.size > 11
      (
        <div id={htmlId_groupTree} class="col-xs-12">
          <ul>{DisplayNodeGroupTree.displayTree(
                  lib
                , Some(fullDisplayCategory(workflowEnabled))
                , Some(showTargetInfo(workflowEnabled))
                , Map()
           )}</ul>
        </div>
      ) ++ Script(OnLoad(
      //build jstree and
      //init bind callback to move
      JsRaw(s"""
        buildGroupTree('#${htmlId_groupTree}','${S.contextPath}', '${selectedNode}', 'off', true, ${CurrentUser.checkRights(Edit("group"))});
        if(${foldCategories}){
          $$('#${htmlId_groupTree}').jstree().close_all();
        }
        $$('#${htmlId_groupTree}').bind("move_node.jstree", function (e,data) {
          var sourceCatId = $$(data.rslt.o).attr("catId");
          var sourceGroupId = $$(data.rslt.o).attr("groupId");
          var destCatId = $$(data.rslt.np).attr("catId");
          if( destCatId ) {
            if(sourceGroupId) {
              var arg = JSON.stringify({ 'sourceGroupId' : sourceGroupId, 'destCatId' : destCatId });
              ${SHtml.ajaxCall(JsVar("arg"), moveGroup(lib, workflowEnabled) _)._2.toJsCmd};
            } else if(  sourceCatId ) {
              var arg = JSON.stringify({ 'sourceCatId' : sourceCatId, 'destCatId' : destCatId });
              ${SHtml.ajaxCall(JsVar("arg"), moveCategory(lib, workflowEnabled) _ )._2.toJsCmd};
            } else {
              alert("Can not move that kind of object");
              $$.jstree.rollback(data.rlbk);
            }
          } else {
            alert("Can not move to something else than a category");
            $$.jstree.rollback(data.rlbk);
          }
        });
        adjustHeight('#groupsTree');
        adjustHeight('#groupDetails');
        $$(window).on('resize',function(){
          adjustHeight('#groupsTree');
          adjustHeight('#groupDetails');
        });
      """))
    )}
  }

  /**
   * Create the popup
   */
  private[this] def createPopup : NodeSeq = {
    creationPopup.get match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent()
    }
  }

  ///////////////////// Callback function for Drag'n'drop in the tree /////////////////////
  private[this] def moveGroup(lib:FullNodeGroupCategory, workflowEnabled: Boolean)(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourceGroupId, destCatId
    try {
      (for {
         JObject(child) <- JsonParser.parse(arg)
         JField("sourceGroupId", JString(sourceGroupId)) <- child
         JField("destCatId", JString(destCatId)) <- child
       } yield {
         (sourceGroupId, destCatId)
       }) match {
        case (sourceGroupId, destCatId) :: Nil =>
          (for {
            result <- woNodeGroupRepository.move(NodeGroupId(sourceGroupId), NodeGroupCategoryId(destCatId), ModificationId(uuidGen.newUuid), CurrentUser.getActor, Some("Group moved by user"))?~! "Error while trying to move group with requested id '%s' to category id '%s'".format(sourceGroupId,destCatId)
            group  <- Box(lib.allGroups.get(NodeGroupId(sourceGroupId))) ?~! s"No such group: ${sourceGroupId}"
          } yield {
            (group.nodeGroup, lib.categoryByGroupId(group.nodeGroup.id))
          }) match {
            case Full((ng,cat)) =>
              refreshGroupLib()
              (
                  refreshTree(htmlTreeNodeId(ng.id.value), workflowEnabled)
                & JsRaw("""setTimeout(function() { $("[groupid=%s]").effect("highlight", {}, 2000)}, 100)""".format(sourceGroupId))
                & refreshRightPanel(GroupForm(ng,cat), workflowEnabled)
              )
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move group with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourceGroupId,destCatId))
          }
        case _ => Alert("Error while trying to move group: bad client parameters")
      }
    } catch {
      case e:Exception => Alert("Error while trying to move group")
    }
  }

  private[this] def moveCategory(lib: FullNodeGroupCategory, workflowEnabled: Boolean)(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourceGroupId, destCatId
    try {
      (for {
         JObject(child) <- JsonParser.parse(arg)
         JField("sourceCatId", JString(sourceCatId)) <- child
         JField("destCatId", JString(destCatId)) <- child
       } yield {
         (sourceCatId, destCatId)
       }) match {
        case (sourceCatId, destCatId) :: Nil =>
          (for {
            category <- Box(lib.allCategories.get(NodeGroupCategoryId(sourceCatId))) ?~! "Error while trying to find category with requested id %s".format(sourceCatId)

            result <- woNodeGroupRepository.saveGroupCategory(
                          category.toNodeGroupCategory
                        , NodeGroupCategoryId(destCatId)
                        , ModificationId(uuidGen.newUuid)
                        , CurrentUser.getActor
                        , reason = None)?~! "Error while trying to move category with requested id '%s' to category id '%s'".format(sourceCatId,destCatId)
          } yield {
            (category.id.value, result)
          }) match {
            case Full((id,res)) =>
              refreshGroupLib
              (
                  refreshTree(htmlTreeNodeId(id), workflowEnabled)
                & OnLoad(JsRaw("""setTimeout(function() { $("[catid=%s]").effect("highlight", {}, 2000);}, 100)""".format(sourceCatId)))
                & refreshRightPanel(CategoryForm(res), workflowEnabled)
              )
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move category with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourceCatId,destCatId))
          }
        case _ => Alert("Error while trying to move group: bad client parameters")
      }
    } catch {
      case e:Exception => Alert("Error while trying to move group")
    }
  }

  ////////////////////

  private[this] def refreshTree(selectedNode:String, workflowEnabled: Boolean) : JsCmd =  {
    Replace(htmlId_groupTree, buildGroupTree(selectedNode, workflowEnabled))
  }

 /********************************************
  * Utility methods for JS
  ********************************************/

  private[this] def displayCategory(workflowEnabled: Boolean)(category : NodeGroupCategory) : JsCmd = {
    selectedCategoryId = Full(category.id)
    //update UI - no modification here, so no refreshGroupLib
    refreshRightPanel(CategoryForm(category), workflowEnabled) &
    JsRaw("""$('#groupDetails').show();""")
  }

  //adaptater
  private[this] def fullDisplayCategory(workflowEnabled: Boolean)(category: FullNodeGroupCategory) = displayCategory(workflowEnabled)(category.toNodeGroupCategory)

  private[this] def showGroupSection(workflowEnabled: Boolean)(g: NodeGroup, parentCategoryId: NodeGroupCategoryId) = {
    refreshRightPanel(GroupForm(g, parentCategoryId), workflowEnabled)&
    JsRaw(s"""
        jQuery('#groupDetails').show();
        var groupId = JSON.stringify({'groupId':'${g.id.value}'});
        window.location.hash = "#"+groupId""")
  }

  private[this] def showTargetInfo(workflowEnabled: Boolean)(parentCategory: FullNodeGroupCategory, targetInfo: FullRuleTargetInfo) : JsCmd = {
    //update UI - no modeification here, so no refreshGroupLib

    //action only for node group

    targetInfo.target match {
      case t:FullGroupTarget => showGroupSection(workflowEnabled)(t.nodeGroup, parentCategory.id)
      case _ => Noop
    }
  }

  private[this] def showPopup(workflowEnabled: Boolean) : JsCmd = {

    boxGroupLib match {
      case eb: EmptyBox => Alert("Error when trying to get the list of categories")
      case Full(rootCategory) =>
        setCreationPopup(rootCategory, workflowEnabled)

        //update UI
        SetHtml("createGroupContainer", createPopup) &
        JsRaw( """ createPopup("createGroupPopup")""")
    }
  }
}