/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.jmap.model

import javax.inject.Inject
import org.apache.james.jmap.mail.MailboxName.MailboxName
import org.apache.james.jmap.mail._
import org.apache.james.jmap.model.UnsignedInt.UnsignedInt
import org.apache.james.jmap.utils.quotas.QuotaLoader
import org.apache.james.mailbox.model.MailboxACL.EntryKey
import org.apache.james.mailbox.model.{MailboxCounters, MailboxId, MailboxMetaData, MailboxPath, MailboxACL => JavaMailboxACL}
import org.apache.james.mailbox.{MailboxSession, Role, SubscriptionManager}
import reactor.core.scala.publisher.SMono

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

object MailboxValidation {
  def validate(mailboxName: Either[IllegalArgumentException, MailboxName],
               unreadEmails: Either[NumberFormatException, UnsignedInt],
               unreadThreads: Either[NumberFormatException, UnsignedInt],
               totalEmails: Either[NumberFormatException, UnsignedInt],
               totalThreads: Either[NumberFormatException, UnsignedInt]): Either[Exception, MailboxValidation] = {
    for {
      validatedName <- mailboxName
      validatedUnreadEmails <- unreadEmails.map(UnreadEmails)
      validatedUnreadThreads <- unreadThreads.map(UnreadThreads)
      validatedTotalEmails <- totalEmails.map(TotalEmails)
      validatedTotalThreads <- totalThreads.map(TotalThreads)
    } yield MailboxValidation(
      mailboxName = validatedName,
      unreadEmails = validatedUnreadEmails,
      unreadThreads = validatedUnreadThreads,
      totalEmails = validatedTotalEmails,
      totalThreads = validatedTotalThreads)
  }
}

case class MailboxValidation(mailboxName: MailboxName,
                             unreadEmails: UnreadEmails,
                             unreadThreads: UnreadThreads,
                             totalEmails: TotalEmails,
                             totalThreads: TotalThreads)

class MailboxFactory @Inject() (subscriptionManager: SubscriptionManager) {

  private def retrieveMailboxName(mailboxPath: MailboxPath, mailboxSession: MailboxSession): Either[IllegalArgumentException, MailboxName] =
    mailboxPath.getName
      .split(mailboxSession.getPathDelimiter)
      .lastOption match {
        case Some(name) => MailboxName.validate(name)
        case None => Left(new IllegalArgumentException("No name for the mailbox found"))
      }

  def create(mailboxMetaData: MailboxMetaData,
             mailboxSession: MailboxSession,
             allMailboxesMetadata: Seq[MailboxMetaData],
             quotaLoader: QuotaLoader): SMono[Either[Exception, Mailbox]] = {

    val id: MailboxId = mailboxMetaData.getId

    val name: Either[IllegalArgumentException, MailboxName] = retrieveMailboxName(mailboxMetaData.getPath, mailboxSession)

    val role: Option[Role] = Role.from(mailboxMetaData.getPath.getName)
      .filter(_ => mailboxMetaData.getPath.belongsTo(mailboxSession)).toScala
    val sortOrder: SortOrder = role.map(SortOrder.getSortOrder).getOrElse(SortOrder.defaultSortOrder)
    val quotas: SMono[Quotas] = quotaLoader.getQuotas(mailboxMetaData.getPath)
    val rights: Rights = Rights.fromACL(MailboxACL.fromJava(mailboxMetaData.getResolvedAcls))

    val sanitizedCounters: MailboxCounters = mailboxMetaData.getCounters.sanitize()
    val unreadEmails: Either[NumberFormatException, UnsignedInt] = UnsignedInt.validate(sanitizedCounters.getUnseen)
    val unreadThreads: Either[NumberFormatException, UnsignedInt] = UnsignedInt.validate(sanitizedCounters.getUnseen)
    val totalEmails: Either[NumberFormatException, UnsignedInt] = UnsignedInt.validate(sanitizedCounters.getCount)
    val totalThreads: Either[NumberFormatException, UnsignedInt] = UnsignedInt.validate(sanitizedCounters.getCount)

    val isOwner = mailboxMetaData.getPath.belongsTo(mailboxSession)
    val aclEntryKey: EntryKey = EntryKey.createUserEntryKey(mailboxSession.getUser)

    val namespace: MailboxNamespace = if (isOwner) {
      PersonalNamespace()
    } else {
      DelegatedNamespace(mailboxMetaData.getPath.getUser)
    }

    val parentPath: Option[MailboxPath] =
      mailboxMetaData.getPath
        .getHierarchyLevels(mailboxSession.getPathDelimiter)
        .asScala
        .reverse
        .drop(1)
        .headOption

    val parentId: Option[MailboxId] = allMailboxesMetadata.filter(otherMetadata => parentPath.contains(otherMetadata.getPath))
      .map(_.getId)
      .headOption

    val myRights: MailboxRights = if (isOwner) {
      MailboxRights.FULL
    } else {
      val rights = Rfc4314Rights.fromJava(mailboxMetaData.getResolvedAcls
        .getEntries
        .getOrDefault(aclEntryKey, JavaMailboxACL.NO_RIGHTS))
        .toRights
      MailboxRights(
        mayReadItems = MayReadItems(rights.contains(Right.Read)),
        mayAddItems = MayAddItems(rights.contains(Right.Insert)),
        mayRemoveItems = MayRemoveItems(rights.contains(Right.DeleteMessages)),
        maySetSeen = MaySetSeen(rights.contains(Right.Seen)),
        maySetKeywords = MaySetKeywords(rights.contains(Right.Write)),
        mayCreateChild = MayCreateChild(false),
        mayRename = MayRename(false),
        mayDelete = MayDelete(false),
        maySubmit = MaySubmit(false))
    }

    def retrieveIsSubscribed: IsSubscribed = IsSubscribed(subscriptionManager
      .subscriptions(mailboxSession)
      .contains(mailboxMetaData.getPath.getName))

    MailboxValidation.validate(name, unreadEmails, unreadThreads, totalEmails, totalThreads) match {
      case Left(error) => SMono.just(Left(error))
      case scala.Right(mailboxValidation) => SMono.fromPublisher(quotas)
        .map(quotas => scala.Right(
          Mailbox(
            id = id,
            name = mailboxValidation.mailboxName,
            parentId = parentId,
            role = role,
            sortOrder = sortOrder,
            unreadEmails = mailboxValidation.unreadEmails,
            totalEmails = mailboxValidation.totalEmails,
            unreadThreads = mailboxValidation.unreadThreads,
            totalThreads = mailboxValidation.totalThreads,
            myRights = myRights,
            namespace = namespace,
            rights = rights,
            quotas = quotas,
            isSubscribed = retrieveIsSubscribed)))
    }
  }
}