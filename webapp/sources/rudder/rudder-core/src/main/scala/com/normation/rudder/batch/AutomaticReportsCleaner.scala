/*
*************************************************************************************
* Copyright 2012 Normation SAS
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

package com.normation.rudder.batch

import net.liftweb.actor.{LAPinger, LiftActor}
import com.normation.rudder.services.system.DatabaseManager
import net.liftweb.common._
import org.joda.time._
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.rudder.domain.reports._
import com.normation.rudder.services.system.DeleteCommand

/**
 *  An helper object designed to help building automatic reports cleaning
 */
object AutomaticReportsCleaning {

  /*
   *  Default parameters and properties name
   */
  val minParam  = "rudder.batch.databasecleaner.runtime.minute"
  val hourParam = "rudder.batch.databasecleaner.runtime.hour"
  val dayParam  = "rudder.batch.databasecleaner.runtime.day"
  val freqParam = "rudder.batch.reportsCleaner.frequency"

  val defaultMinute = 0
  val defaultHour   = 0
  val defaultDay    = "sunday"

  val defaultArchiveTTL = 30
  val defaultDeleteTTL  = 90

  /**
   *  Build a frequency depending on the value
   */
  def buildFrequency(kind:String, min:Int, hour:Int, day:String):Box[CleanFrequency] = {
    kind.toLowerCase() match {
      case "hourly" => buildHourly(min)
      case "daily"  => buildDaily(min,hour)
      case "weekly" => buildWeekly(min,hour,day)
      case _ =>     Failure("%s is not correctly set, value is %s".format(freqParam,kind))
    }

  }

  /**
   *  Build an hourly frequency
   */
  private[this] def buildHourly(min:Int):Box[CleanFrequency] = {

    if (min >= 0 && min <= 59)
      Full(Hourly(min))
    else
      Failure("%s is not correctly set, value is %d, should be in [0-59]".format(minParam,min))
  }

  /**
   *  Build a daily frequency
   */
  private[this] def buildDaily(min:Int,hour:Int):Box[CleanFrequency] = {

    if (min >= 0 && min <= 59)
      if(hour >= 0 && hour <= 23)
        Full(Daily(hour,min))
      else
        Failure("%s is not correctly set, value is %d, should be in [0-23]".format(hourParam,hour))
    else
      Failure("%s is not correctly set, value is %d, should be in [0-59]".format(minParam,min))
  }

  /**
   *  Build a weekly frequency
   */
  private[this] def buildWeekly(min:Int,hour:Int,day:String):Option[CleanFrequency] = {

    if (min >= 0 && min <= 59)
      if(hour >= 0 && hour <= 23)
        day.toLowerCase() match {
          case "monday"    => Full(Weekly(DateTimeConstants.MONDAY,hour,min))
          case "tuesday"   => Full(Weekly(DateTimeConstants.TUESDAY,hour,min))
          case "wednesday" => Full(Weekly(DateTimeConstants.WEDNESDAY,hour,min))
          case "thursday"  => Full(Weekly(DateTimeConstants.THURSDAY,hour,min))
          case "friday"    => Full(Weekly(DateTimeConstants.FRIDAY,hour,min))
          case "saturday"  => Full(Weekly(DateTimeConstants.SATURDAY,hour,min))
          case "sunday"    => Full(Weekly(DateTimeConstants.SUNDAY,hour,min))
          case _           => Failure("%s is not correctly set, value is %s".format(dayParam,day))
      }
      else
        Failure("%s is not correctly set, value is %d, should be in [0-23]".format(hourParam,hour))
    else
      Failure("%s is not correctly set, value is %d, should be in [0-59]".format(minParam,min))
  }
}

/**
 *  Clean Frequency represents how often a report cleaning will be done.
 */
trait CleanFrequency {

  /**
   *  Check if report cleaning has to be run
   *  Actually check every minute.
   *  TODO : check in a range of 5 minutes
   */
  def check(date:DateTime):Boolean = {
    val target = checker(date)
    target.equals(date)
  }

  /**
   *  Compute the checker from now
   */
  def checker(now: DateTime):DateTime

  /**
   *  Compute the next cleaning time
   */
  def next:DateTime

  /**
   *  Display the frequency
   */
  def displayFrequency : Option[String]

  override def toString = displayFrequency match {
    case Some(freq) => freq
    case None => "Could not compute frequency"
  }

}

/**
 *  An hourly frequency.
 *  It runs every hour past min minutes
 */
case class Hourly(min:Int) extends CleanFrequency{

  def checker(date:DateTime):DateTime = date.withMinuteOfHour(min)

  def next:DateTime = {
    val now = DateTime.now()
    if (now.isBefore(checker(now)))
      checker(now)
    else
      checker(now).plusHours(1)
  }

   def displayFrequency = Some("Every hour past %d minutes".format(min))

}

/**
 *  A daily frequency.
 *  It runs every day at hour:min
 */
case class Daily(hour:Int,min:Int) extends CleanFrequency{

  def checker(date:DateTime):DateTime = date.withMinuteOfHour(min).withHourOfDay(hour)

  def next:DateTime = {
    val now = DateTime.now()
    if (now.isBefore(checker(now)))
      checker(now)
    else
      checker(now).plusDays(1)
  }

  def displayFrequency = Some("Every day at %02d:%02d".format(hour,min))

}

/**
 *  A weekly frequency.
 *  It runs every week on day at hour:min
 */
case class Weekly(day:Int,hour:Int,min:Int) extends CleanFrequency{

  def checker(date:DateTime):DateTime = date.withMinuteOfHour(min).withHourOfDay(hour).withDayOfWeek(day)

  def next:DateTime = {
    val now = DateTime.now()
    if (now.isBefore(checker(now)))
      checker(now)
    else
      checker(now).plusWeeks(1)
  }


  def displayFrequency = {
    def expressWeekly(day:String) = Some("every %s at %02d:%02d".format(day,hour,min))
    day match {
      case DateTimeConstants.MONDAY    => expressWeekly ("Monday")
      case DateTimeConstants.TUESDAY   => expressWeekly ("Tuesday")
      case DateTimeConstants.WEDNESDAY => expressWeekly ("Wednesday")
      case DateTimeConstants.THURSDAY  => expressWeekly ("Thursday")
      case DateTimeConstants.FRIDAY    => expressWeekly ("Friday")
      case DateTimeConstants.SATURDAY  => expressWeekly ("Saturday")
      case DateTimeConstants.SUNDAY    => expressWeekly ("Sunday")
      case _ => None
    }
  }

}

// States into which the cleaner process can be.
sealed trait CleanerState
// The process is idle.
case object IdleCleaner extends CleanerState
// An update is currently cleaning the databases.
case object ActiveCleaner extends CleanerState

sealed trait DatabaseCleanerMessage
// Messages the cleaner can receive.
// Ask to clean database (need to be in active state).
case object CleanDatabase extends DatabaseCleanerMessage
// Ask to check if cleaning has to be launched (need to be in idle state).
case object CheckLaunch extends DatabaseCleanerMessage

case class ManualLaunch(date:DateTime) extends DatabaseCleanerMessage

trait DatabaseCleanerActor extends LiftActor {
  def isIdle : Boolean
}
/**
 *  A class that periodically check if the Database has to be cleaned.
 *
 *  for now, Archive and delete run at same frequency.
 *  Delete and Archive TTL express the maximum age of reports.
 *  A negative or zero TTL means to not run the relative reports cleaner.
 *  Archive action doesn't run if its TTL is more than Delete TTL.
 */
case class AutomaticReportsCleaning(
    dbManager         : DatabaseManager
  , deletettl         : Int // in days
  , archivettl        : Int // in days
  , complianceLevelttl: Int // in days
  , freq              : CleanFrequency
) extends Loggable {
  val reportLogger = ReportLogger

  // Check if automatic reports archiving has to be started
  val archiver:DatabaseCleanerActor = if(archivettl < 1) {
    val propertyName = "rudder.batch.reportsCleaner.archive.TTL"
    reportLogger.info("Disable automatic database archive sinces property %s is 0 or negative".format(propertyName))
    new LADatabaseCleaner(ArchiveAction(dbManager,this),-1, complianceLevelttl)
  } else {
    // Don't launch automatic report archiving if reports would have already been deleted by automatic reports deleting
    if ((archivettl < deletettl ) && (deletettl > 0)) {
      logger.trace("***** starting Automatic Archive Reports batch *****")
      new LADatabaseCleaner(ArchiveAction(dbManager,this),archivettl, complianceLevelttl)
    }
    else {
      reportLogger.info("Disable automatic archive since archive maximum age is older than delete maximum age")
      new LADatabaseCleaner(ArchiveAction(dbManager,this),-1, complianceLevelttl)
    }
  }
  archiver ! CheckLaunch

  val deleter:DatabaseCleanerActor = if(deletettl < 1) {
    val propertyName = "rudder.batch.reportsCleaner.delete.TTL"
    reportLogger.info("Disable automatic database deletion sinces property %s is 0 or negative".format(propertyName))
    new LADatabaseCleaner(DeleteAction(dbManager,this),-1, complianceLevelttl)
  } else {
    logger.trace("***** starting Automatic Delete Reports batch *****")
    new LADatabaseCleaner(DeleteAction(dbManager,this), deletettl, complianceLevelttl)
  }
  deleter ! CheckLaunch

  ////////////////////////////////////////////////////////////////
  //////////////////// implementation details ////////////////////
  ////////////////////////////////////////////////////////////////

  private case class LADatabaseCleaner(cleanaction:CleanReportAction, reportsttl:Int, compliancettl:Int) extends DatabaseCleanerActor with Loggable {
    updateManager =>

    private[this] val reportLogger = ReportLogger
    private[this] val automatic = reportsttl > 0 
    // compliancettl may be disabled, it's managed with the Option[DeleteCommand.ComplianceLevel]
    // We don't handle the case where compliancelevel cleaning would be enabled and reports one
    // disable, because really, if someone has enought free space to keep ruddersysevent forever, 
    // he can handle compliance forever, too.  
    private[this] var currentState: CleanerState = IdleCleaner
    private[this] var lastRun: DateTime = DateTime.now()

    def isIdle : Boolean = currentState == IdleCleaner

    private[this] def formatDate(date:DateTime) : String = date.toString("yyyy-MM-dd HH:mm")

    private[this] def activeCleaning(reports: DeleteCommand.Reports, compliances: Option[DeleteCommand.ComplianceLevel], message : DatabaseCleanerMessage, kind:String) : Unit = {
      val formattedDate = formatDate(reports.date)
      cleanaction.act(reports, compliances) match {
        case eb:EmptyBox =>
          // Error while cleaning. Do not start again, since there is heavy chance
          // that without an human intervention, it will fail again, leading to
          // log explosion. Perhaps we could start-it again after a little time (several minutes)
          reportLogger.error("Reports database: Error while processing database %s, cause is: %s ".format(cleanaction.continue.toLowerCase(),eb))
          currentState = IdleCleaner
        case Full(res) =>
          if (res==0)
            reportLogger.info("Reports database: %s %s completed for all reports before %s, no reports to %s".format(kind,cleanaction.name.toLowerCase(), formattedDate,cleanaction.name.toLowerCase()))
          else
            reportLogger.info("Reports database: %s %s completed for all reports before %s, %d reports %s".format(kind,cleanaction.name.toLowerCase(),formattedDate,res,cleanaction.past.toLowerCase()))
          lastRun=DateTime.now
          currentState = IdleCleaner
      }
    }

    override protected def messageHandler = {
      /*
       * Ask to check if need to be launched
       * If idle   => check
       * If active => do nothing
       * always register to LAPinger
       */
      case CheckLaunch => {
        // Schedule next check, every minute
        if (automatic) {
          LAPinger.schedule(this, CheckLaunch, 1000L*60)
          currentState match {
            case IdleCleaner =>
            logger.trace("***** Check launch *****")
            if(freq.check(DateTime.now)){
              logger.trace("***** Automatic %s entering in active State *****".format(cleanaction.name.toLowerCase()))
              currentState = ActiveCleaner
              (this) ! CleanDatabase
            }
            else
              logger.trace("***** Automatic %s will not be launched now, It is scheduled '%s'*****".format(cleanaction.name.toLowerCase(),freq.toString))

            case ActiveCleaner => ()
          }
        }
        else
          logger.trace("***** Database %s is not automatic, it will not schedule its next launch *****".format(cleanaction.name))
      }
      /*
       * Ask to clean Database
       * If idle   => do nothing
       * If active => clean database
       */
      case CleanDatabase => {
        currentState match {

          case ActiveCleaner =>
            val now = DateTime.now
            val reportsCommand = DeleteCommand.Reports(now.minusDays(reportsttl))
            val complianceCommand = if(compliancettl > 0) {
              Some(DeleteCommand.ComplianceLevel(now.minusDays(compliancettl)))
            } else {
              None
            }
            val formattedDate = formatDate(reportsCommand.date)
            logger.trace("***** %s Database *****".format(cleanaction.name))
            reportLogger.info(s"Reports database: Automatic ${cleanaction.name.toLowerCase()} started for all reports before ${formattedDate}")
            complianceCommand.foreach { c =>
              reportLogger.info(s"Compliance level database: Automatic ${cleanaction.name.toLowerCase()} started for all compliance levels reports before ${formatDate(c.date)}")
            }
            activeCleaning(reportsCommand, complianceCommand, CleanDatabase,"automatic")

          case IdleCleaner => ()
        }
      }

      case ManualLaunch(date) => {
        val formattedDate = formatDate(date)
        logger.trace("***** Ask to launch manual database %s  *****".format(cleanaction.name))
        currentState match {
        case IdleCleaner =>
              currentState = ActiveCleaner
              logger.trace("***** Start manual %s database *****".format(cleanaction.name))
              reportLogger.info("Reports database: Manual %s started for all reports before %s ".format(cleanaction.name.toLowerCase(), formattedDate))
              activeCleaning(DeleteCommand.Reports(date),None,ManualLaunch(date),"Manual")

        case ActiveCleaner => reportLogger.info("Reports database: A database cleaning is already running, please try later")
        }
      }
      case _ =>
        reportLogger.error("Wrong message for automatic reports %s ".format(cleanaction.name.toLowerCase()))
    }
  }
}