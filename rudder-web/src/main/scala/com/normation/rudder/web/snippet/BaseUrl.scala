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

package com.normation.rudder.web.snippet

import scala.xml._
import net.liftweb.http._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE._
import net.liftweb.util._
import com.normation.rudder.web.services.GetBaseUrlService
import bootstrap.liftweb.RudderConfig
import bootstrap.liftweb.StaticResourceRewrite

/**
 *
 * This class deals with the base URL of the application.
 * For now, it only get the url from a property, but it
 * should also be able to deal with http/https, etc.
 */
class BaseUrl {

  /*
   * We still need to have a base url for some javascript
   * But now we use it as a javascript variable
   */
  def display = Script(JsRaw(s"""var contextPath = '${S.contextPath}'; var resourcesPath = '${S.contextPath}/${StaticResourceRewrite.prefix}'"""))

/*I keep the old base url as a reminder <base href={(urlService.baseUrl getOrElse S.hostAndPath)+"/"} />*/
}
