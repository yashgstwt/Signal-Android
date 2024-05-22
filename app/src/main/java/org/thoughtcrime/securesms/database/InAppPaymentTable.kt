/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Parcelable
import androidx.core.content.contentValuesOf
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import org.signal.core.util.DatabaseId
import org.signal.core.util.DatabaseSerializer
import org.signal.core.util.Serializer
import org.signal.core.util.delete
import org.signal.core.util.exists
import org.signal.core.util.insertInto
import org.signal.core.util.readToList
import org.signal.core.util.readToSingleObject
import org.signal.core.util.requireBoolean
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullBlob
import org.signal.core.util.requireString
import org.signal.core.util.select
import org.signal.core.util.update
import org.signal.core.util.updateAll
import org.signal.core.util.withinTransaction
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.DonationErrorSource
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.util.parcelers.MillisecondDurationParceler
import org.thoughtcrime.securesms.util.parcelers.NullableSubscriberIdParceler
import org.whispersystems.signalservice.api.subscriptions.SubscriberId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Centralizes state information for donations and backups payments and redemption.
 *
 * Each entry in this database has a 1:1 relationship with a redeemable token, which can be for one of the following:
 * * A Gift Badge
 * * A Boost Badge
 * * A Subscription Badge
 * * A Backup Subscription
 */
class InAppPaymentTable(context: Context, databaseHelper: SignalDatabase) : DatabaseTable(context, databaseHelper) {

  companion object {
    private const val TABLE_NAME = "in_app_payment"

    /**
     * Row ID
     */
    private const val ID = "_id"

    /**
     * What kind of payment this row represents
     */
    private const val TYPE = "type"

    /**
     * The current state of the given payment
     */
    private const val STATE = "state"

    /**
     * When the payment was first inserted into the database
     */
    private const val INSERTED_AT = "inserted_at"

    /**
     * The last time the payment was updated
     */
    private const val UPDATED_AT = "updated_at"

    /**
     * Whether the user has been notified of the payment's terminal state.
     */
    private const val NOTIFIED = "notified"

    /**
     * The subscriber id associated with the payment.
     */
    private const val SUBSCRIBER_ID = "subscriber_id"

    /**
     * The end of period related to the subscription, if this column represents a recurring payment.
     * A zero here indicates that we do not have an end of period yet for this recurring payment, OR
     * that this row does not represent a recurring payment.
     */
    private const val END_OF_PERIOD = "end_of_period"

    /**
     * Extraneous data that may or may not be common among payments
     */
    private const val DATA = "data"

    val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY,
        $TYPE INTEGER NOT NULL,
        $STATE INTEGER NOT NULL,
        $INSERTED_AT INTEGER NOT NULL,
        $UPDATED_AT INTEGER NOT NULL,
        $NOTIFIED INTEGER DEFAULT 1,
        $SUBSCRIBER_ID TEXT,
        $END_OF_PERIOD INTEGER DEFAULT 0,
        $DATA BLOB NOT NULL
      )
    """.trimIndent()
  }

  /**
   * Called when we create a new InAppPayment while in the checkout screen. At this point in
   * the flow, we know that there should not be any other InAppPayment objects currently in this
   * state.
   */
  fun clearCreated() {
    writableDatabase.delete(TABLE_NAME)
      .where("$STATE = ?", State.serialize(State.CREATED))
      .run()
  }

  fun insert(
    type: Type,
    state: State,
    subscriberId: SubscriberId?,
    endOfPeriod: Duration?,
    inAppPaymentData: InAppPaymentData
  ): InAppPaymentId {
    val now = System.currentTimeMillis()
    return writableDatabase.insertInto(TABLE_NAME)
      .values(
        TYPE to type.code,
        STATE to state.code,
        INSERTED_AT to now,
        UPDATED_AT to now,
        SUBSCRIBER_ID to subscriberId?.serialize(),
        END_OF_PERIOD to (endOfPeriod?.inWholeSeconds ?: 0L),
        DATA to InAppPaymentData.ADAPTER.encode(inAppPaymentData),
        NOTIFIED to 1
      )
      .run()
      .let { InAppPaymentId(it) }
  }

  fun update(
    inAppPayment: InAppPayment
  ) {
    val updated = inAppPayment.copy(updatedAt = System.currentTimeMillis().milliseconds)
    writableDatabase.update(TABLE_NAME)
      .values(InAppPayment.serialize(updated))
      .where(ID_WHERE, inAppPayment.id)
      .run()

    AppDependencies.databaseObserver.notifyInAppPaymentsObservers(inAppPayment)
  }

  fun getAllWaitingForAuth(): List<InAppPayment> {
    return readableDatabase.select()
      .from(TABLE_NAME)
      .where("$STATE = ?", State.serialize(State.WAITING_FOR_AUTHORIZATION))
      .run()
      .readToList { InAppPayment.deserialize(it) }
  }

  fun consumeInAppPaymentsToNotifyUser(): List<InAppPayment> {
    return writableDatabase.withinTransaction { db ->
      val payments = db.select()
        .from(TABLE_NAME)
        .where("$NOTIFIED = ?", 0)
        .run()
        .readToList(mapper = { InAppPayment.deserialize(it) })

      db.updateAll(TABLE_NAME).values(NOTIFIED to 1).run()

      payments
    }
  }

  fun getById(id: InAppPaymentId): InAppPayment? {
    return readableDatabase.select()
      .from(TABLE_NAME)
      .where(ID_WHERE, id)
      .run()
      .readToSingleObject(InAppPayment.Companion)
  }

  fun getByEndOfPeriod(type: Type, endOfPeriod: Duration): InAppPayment? {
    return readableDatabase.select()
      .from(TABLE_NAME)
      .where("$TYPE = ? AND $END_OF_PERIOD = ?", Type.serialize(type), endOfPeriod.inWholeSeconds)
      .run()
      .readToSingleObject(InAppPayment.Companion)
  }

  fun getByLatestEndOfPeriod(type: Type): InAppPayment? {
    return readableDatabase.select()
      .from(TABLE_NAME)
      .where("$TYPE = ? AND $END_OF_PERIOD > 0", Type.serialize(type))
      .orderBy("$END_OF_PERIOD DESC")
      .limit(1)
      .run()
      .readToSingleObject(InAppPayment.Companion)
  }

  /**
   * Returns the latest entry in the table for the given subscriber id.
   */
  fun getLatestBySubscriberId(subscriberId: SubscriberId): InAppPayment? {
    return readableDatabase.select()
      .from(TABLE_NAME)
      .where("$SUBSCRIBER_ID = ?", subscriberId.serialize())
      .orderBy("$END_OF_PERIOD DESC")
      .limit(1)
      .run()
      .readToSingleObject(InAppPayment.Companion)
  }

  fun markSubscriptionManuallyCanceled(subscriberId: SubscriberId) {
    writableDatabase.withinTransaction {
      val inAppPayment = getLatestBySubscriberId(subscriberId) ?: return@withinTransaction

      update(
        inAppPayment.copy(
          data = inAppPayment.data.copy(
            cancellation = InAppPaymentData.Cancellation(
              reason = InAppPaymentData.Cancellation.Reason.MANUAL
            )
          )
        )
      )
    }
  }

  /**
   * Returns whether there are any pending donations in the database.
   */
  fun hasPendingDonation(): Boolean {
    return readableDatabase.exists(TABLE_NAME)
      .where(
        "$STATE = ? AND ($TYPE = ? OR $TYPE = ? OR $TYPE = ?)",
        State.serialize(State.PENDING),
        Type.serialize(Type.RECURRING_DONATION),
        Type.serialize(Type.ONE_TIME_DONATION),
        Type.serialize(Type.ONE_TIME_GIFT)
      )
      .run()
  }

  /**
   * Returns whether there are any pending donations in the database.
   */
  fun hasPending(type: Type): Boolean {
    return readableDatabase.exists(TABLE_NAME)
      .where(
        "$STATE = ? AND $TYPE = ?",
        State.serialize(State.PENDING),
        Type.serialize(type)
      )
      .run()
  }

  /**
   * Retrieves from the database the latest payment of the given type that is either in the PENDING or WAITING_FOR_AUTHORIZATION state.
   */
  fun getLatestInAppPaymentByType(type: Type): InAppPayment? {
    return readableDatabase.select()
      .from(TABLE_NAME)
      .where(
        "($STATE = ? OR $STATE = ? OR $STATE = ?) AND $TYPE = ?",
        State.serialize(State.PENDING),
        State.serialize(State.WAITING_FOR_AUTHORIZATION),
        State.serialize(State.END),
        Type.serialize(type)
      )
      .orderBy("$INSERTED_AT DESC")
      .limit(1)
      .run()
      .readToSingleObject(InAppPayment.Companion)
  }

  /**
   * Represents a database row. Nicer than returning a raw value.
   */
  @Parcelize
  data class InAppPaymentId(
    val rowId: Long
  ) : DatabaseId, Parcelable {
    init {
      check(rowId > 0)
    }

    override fun serialize(): String = rowId.toString()

    override fun toString(): String = serialize()
  }

  /**
   * Represents a single token payment.
   */
  @Parcelize
  @TypeParceler<Duration, MillisecondDurationParceler>
  @TypeParceler<SubscriberId?, NullableSubscriberIdParceler>
  data class InAppPayment(
    val id: InAppPaymentId,
    val type: Type,
    val state: State,
    val insertedAt: Duration,
    val updatedAt: Duration,
    val notified: Boolean,
    val subscriberId: SubscriberId?,
    val endOfPeriod: Duration,
    val data: InAppPaymentData
  ) : Parcelable {

    @IgnoredOnParcel
    val endOfPeriodSeconds: Long = endOfPeriod.inWholeSeconds

    companion object : DatabaseSerializer<InAppPayment> {

      override fun serialize(data: InAppPayment): ContentValues {
        return contentValuesOf(
          ID to data.id.serialize(),
          TYPE to data.type.apply { check(this != Type.UNKNOWN) }.code,
          STATE to data.state.code,
          INSERTED_AT to data.insertedAt.inWholeSeconds,
          UPDATED_AT to data.updatedAt.inWholeSeconds,
          NOTIFIED to data.notified,
          SUBSCRIBER_ID to data.subscriberId?.serialize(),
          END_OF_PERIOD to data.endOfPeriod.inWholeSeconds,
          DATA to data.data.encode()
        )
      }

      override fun deserialize(input: Cursor): InAppPayment {
        return InAppPayment(
          id = InAppPaymentId(input.requireLong(ID)),
          type = Type.deserialize(input.requireInt(TYPE)),
          state = State.deserialize(input.requireInt(STATE)),
          insertedAt = input.requireLong(INSERTED_AT).seconds,
          updatedAt = input.requireLong(UPDATED_AT).seconds,
          notified = input.requireBoolean(NOTIFIED),
          subscriberId = input.requireString(SUBSCRIBER_ID)?.let { SubscriberId.deserialize(it) },
          endOfPeriod = input.requireLong(END_OF_PERIOD).seconds,
          data = InAppPaymentData.ADAPTER.decode(input.requireNonNullBlob(DATA))
        )
      }
    }
  }

  enum class Type(val code: Int, val recurring: Boolean) {
    /**
     * Used explicitly for mapping DonationErrorSource. Writing this value
     * into an InAppPayment is an error.
     */
    UNKNOWN(-1, false),

    /**
     * This payment is for a gift badge
     */
    ONE_TIME_GIFT(0, false),

    /**
     * This payment is for a one-time donation
     */
    ONE_TIME_DONATION(1, false),

    /**
     * This payment is for a recurring donation
     */
    RECURRING_DONATION(2, true),

    /**
     * This payment is for a recurring backup payment
     */
    RECURRING_BACKUP(3, true);

    companion object : Serializer<Type, Int> {
      override fun serialize(data: Type): Int = data.code
      override fun deserialize(input: Int): Type = values().first { it.code == input }
    }

    fun toErrorSource(): DonationErrorSource {
      return when (this) {
        UNKNOWN -> DonationErrorSource.UNKNOWN
        ONE_TIME_GIFT -> DonationErrorSource.GIFT
        ONE_TIME_DONATION -> DonationErrorSource.ONE_TIME
        RECURRING_DONATION -> DonationErrorSource.MONTHLY
        RECURRING_BACKUP -> DonationErrorSource.UNKNOWN // TODO [message-backups] error handling
      }
    }

    fun toSubscriberType(): InAppPaymentSubscriberRecord.Type? {
      return when (this) {
        RECURRING_BACKUP -> InAppPaymentSubscriberRecord.Type.BACKUP
        RECURRING_DONATION -> InAppPaymentSubscriberRecord.Type.DONATION
        else -> null
      }
    }

    fun requireSubscriberType(): InAppPaymentSubscriberRecord.Type {
      return requireNotNull(toSubscriberType())
    }
  }

  /**
   * Represents the payment pipeline state for a given in-app payment
   *
   * ```mermaid
   * flowchart TD
   *     CREATED -- Auth required --> WAITING_FOR_AUTHORIZATION
   *     CREATED -- Auth not required --> PENDING
   *     WAITING_FOR_AUTHORIZATION -- User completes auth --> PENDING
   *     WAITING_FOR_AUTHORIZATION -- User does not complete auth --> END
   *     PENDING --> END
   *     PENDING --> RETRY
   *     PENDING --> END
   *     RETRY --> PENDING
   *     RETRY --> END
   * ```
   */
  enum class State(val code: Int) {
    /**
     * This payment has been created, but not submitted for processing yet.
     */
    CREATED(0),

    /**
     * This payment is awaiting the user to return from an external authorization2
     * such as a 3DS flow or IDEAL confirmation.
     */
    WAITING_FOR_AUTHORIZATION(1),

    /**
     * This payment is authorized and is waiting to be processed.
     */
    PENDING(2),

    /**
     * This payment pipeline has been completed. Check the data to see the state.
     */
    END(3);

    companion object : Serializer<State, Int> {
      override fun serialize(data: State): Int = data.code
      override fun deserialize(input: Int): State = State.values().first { it.code == input }
    }
  }
}
