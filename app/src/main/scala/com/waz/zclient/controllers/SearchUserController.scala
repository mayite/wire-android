/**
 * Wire
 * Copyright (C) 2017 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.controllers

import com.waz.ZLog
import com.waz.ZLog.ImplicitTag._
import com.waz.api.{Contact, Contacts, UpdateListener}
import com.waz.model.SearchQuery.{Recommended, RecommendedHandle, TopPeople}
import com.waz.model._
import com.waz.service.{SearchKey, ZMessaging}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.SeqMap
import com.waz.utils.events.{EventContext, EventStream, RefreshingSignal, Signal}
import com.waz.zclient.utils.{ConversationMembersSignal, SearchUtils, UiStorage}
import com.waz.zclient.{Injectable, Injector}

import scala.collection.immutable.Set

case class SearchState(filter: String, hasSelectedUsers: Boolean, addingToConversation: Option[ConvId], teamId: Option[TeamId] = None){
  val shouldShowTopUsers = filter.isEmpty && teamId.isEmpty && addingToConversation.isEmpty
  val shouldShowAbContacts = addingToConversation.isEmpty && !hasSelectedUsers && teamId.isEmpty
  val shouldShowGroupConversations = filter.nonEmpty && !hasSelectedUsers && addingToConversation.isEmpty
  val shouldShowDirectorySearch = filter.nonEmpty && !hasSelectedUsers && addingToConversation.isEmpty
}

class SearchUserController(initialState: SearchState)(implicit injector: Injector, ec: EventContext) extends Injectable {
  implicit private val uiStorage = inject[UiStorage]

  private val zms = inject[Signal[ZMessaging]]

  val searchState = Signal(initialState)

  var selectedUsers = Set[UserId]()
  var excludedUsers = for {
    z <- zms
    searchState <- searchState
    members <- searchState.addingToConversation.fold(Signal.const(Set[UserId]()))(ConversationMembersSignal(_))
  } yield members.filterNot(_ == z.selfUserId)

  val onSelectedUserAdded = EventStream[UserId]()
  val onSelectedUserRemoved = EventStream[UserId]()
  val selectedUsersSignal = new RefreshingSignal[Set[UserId], UserId](CancellableFuture.successful(selectedUsers), EventStream.union(onSelectedUserAdded, onSelectedUserRemoved))
  EventStream.union(onSelectedUserAdded, onSelectedUserRemoved).on(Threading.Ui) { _ =>
    setHasSelectedUsers(selectedUsers.nonEmpty)
  }

  def addUser(userId: UserId): Unit = {
    selectedUsers = selectedUsers ++ Set(userId)
    onSelectedUserAdded ! userId
  }

  def removeUser(userId: UserId): Unit = {
    selectedUsers = selectedUsers -- Set(userId)
    onSelectedUserRemoved ! userId
  }

  def setFilter(filter: String): Unit = {
    searchState.mutate(_.copy(filter = filter))
  }

  private def setHasSelectedUsers(hasSelectedUsers: Boolean): Unit = {
    searchState.mutate(_.copy(hasSelectedUsers = hasSelectedUsers))
  }

  def setState(filter: String, hasSelectedUsers: Boolean, addingToConversation: Option[ConvId], teamId: Option[TeamId]): Unit = {
    searchState ! SearchState(filter, hasSelectedUsers, addingToConversation, teamId)
  }

  val topUsersSignal = for {
    z <- zms
    users <- z.userSearch.searchUserData(TopPeople)
    excludedUsers <- excludedUsers
    searchState <- searchState
  } yield if (searchState.shouldShowTopUsers) users.values.filter(u => !excludedUsers.contains(u.id)) else IndexedSeq.empty[UserData]

  val conversationsSignal = for {
    z <- zms
    searchState <- searchState
    convs <- if (searchState.shouldShowGroupConversations)
      Signal.future(z.convsUi.findGroupConversations(SearchKey(searchState.filter), Int.MaxValue, handleOnly = Handle.containsSymbol(searchState.filter)))
    else
      Signal(Seq[ConversationData]())
  } yield convs.filter{ conv =>
    searchState.teamId match {
      case Some(tId) => conv.team.contains(tId)
      case _ => true
    }
  }.distinct

  private val localSearchSignal = for {
    z                   <- zms
    searchState         <- searchState
    acceptedOrBlocked   <- z.users.acceptedOrBlockedUsers
    members             <- searchTeamMembersForState(z, searchState)
    excludedIds         <- excludedUsers
    usersAlreadyInConv  <- searchConvMembersForState(z, searchState)
  } yield sortUsers(acceptedOrBlocked.values.toSet, members, excludedIds ++ usersAlreadyInConv, z.selfUserId, searchState)

  private def sortUsers(connected: Set[UserData],
                        members: Set[UserData],
                        excludedIds: Set[UserId],
                        selfId: UserId,
                        searchState: SearchState) = {

    ZLog.debug(s"UU connected: ${connected.map(_.displayName)}")
    ZLog.debug(s"UU members: ${members.map(_.displayName)}")
    val users = if (searchState.filter.nonEmpty) connected.filter(SearchUtils.ConnectedUsersPredicate(
      searchState.filter,
      excludedIds.map(_.str),
      alsoSearchByEmail = true,
      showBlockedUsers = true,
      searchByHandleOnly = Handle.containsSymbol(searchState.filter))) else connected

    ZLog.debug(s"UU users: ${users.map(_.displayName)}")

    (users ++ members).toVector
      .filterNot(u => u.id == selfId || excludedIds.contains(u.id))
      .sortBy(_.getDisplayName)
  }

  private def searchConvMembersForState(z: ZMessaging, searchState: SearchState) = searchState.addingToConversation match {
    case None => Signal.const(Set.empty[UserId])
    case Some(convId) => Signal.future(z.membersStorage.getByConv(convId)).map(_.map(_.userId).toSet)
  }

  private def searchTeamMembersForState(z:ZMessaging, searchState: SearchState) = searchState.teamId match {
    case None => Signal.const(Set.empty[UserData])
    case Some(_) =>
      val searchKey = if (searchState.filter.isEmpty) None else Some(SearchKey(searchState.filter))
      z.teams.searchTeamMembers(searchKey, handleOnly = Handle.containsSymbol(searchState.filter))
  }

  val searchSignal = for {
    z <- zms
    searchState <- searchState
    users <- if (searchState.shouldShowDirectorySearch)
      z.userSearch.searchUserData(getSearchQuery(searchState.filter))
    else
      Signal(SeqMap.empty[UserId, UserData])
    excludedUsers <- excludedUsers
  } yield users.values.filter(u => !excludedUsers.contains(u.id))

  //TODO: remove this old api....
  val contactsSignal = Signal[Seq[Contact]]()
  var uiContacts: Option[Contacts] = None
  searchState.on(Threading.Ui) {
    case SearchState(filter , false, None, None) =>
      uiContacts.foreach(_.search(filter))
    case _ =>
  }
  private val contactsUpdateListener: UpdateListener = new UpdateListener() {
    def updated(): Unit = {
      uiContacts.foreach(contacts =>  contactsSignal ! (0 until contacts.size()).map(contacts.get).filter(c => c.getUser == null))
    }
  }
  def setContacts(contacts: Contacts): Unit = {
    uiContacts.foreach(_.removeUpdateListener(contactsUpdateListener))
    uiContacts = Some(contacts)
    uiContacts.foreach(_.addUpdateListener(contactsUpdateListener))
    contactsUpdateListener.updated()
  }

  val allDataSignal = for {
    topUsers              <- topUsersSignal.map(Option(_)).orElse(Signal.const(Option.empty[IndexedSeq[UserData]]))
    localResults          <- localSearchSignal.map(Option(_)).orElse(Signal.const(Option.empty[Vector[UserData]]))
    conversations         <- conversationsSignal.map(Option(_)).orElse(Signal.const(Option.empty[Seq[ConversationData]]))
    searchState           <- searchState
    contacts              <- if (searchState.shouldShowAbContacts) contactsSignal.orElse(Signal.const(Seq.empty[Contact])) else Signal(Seq.empty[Contact])
    directoryResults      <- searchSignal.map(Option(_)).orElse(Signal.const(Option.empty[IndexedSeq[UserData]]))
  } yield (topUsers, localResults, conversations, contacts, directoryResults)

  private def getSearchQuery(str: String): SearchQuery = if (Handle.containsSymbol(str)) RecommendedHandle(str) else Recommended(str)

}