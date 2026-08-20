package demeter.ingestion

import java.nio.charset.StandardCharsets
import java.time.{Instant, OffsetDateTime}

import scala.util.Try

import demeter.foundations._
import demeter.foundations.DealWatchError.Decode
import io.circe.{ACursor, Json}

/** Spec 01.5 — pure decoders from Flipp JSON to domain objects.
  *
  * Lenient in a disciplined way: unknown fields are ignored (Flipp adds fields
  * regularly); a price may be a number, a numeric string, or null; data that
  * would corrupt the domain (inverted windows, non-numeric non-null prices) is
  * rejected at the item level with a JSON pointer, and the envelope decoders
  * drop-and-count rather than failing the whole response (01.4 policy).
  */
object FlippDecoders {

  final case class ListingParse(flyers: List[Flyer], merchants: List[Merchant], dropped: Int)
  final case class ItemsParse(items: List[FlyerItem], dropped: Int)
  final case class SearchParse(
      flyerItems: List[FlyerItem],
      ecomItems: List[EcomItem],
      merchants: List[Merchant],
      normalizedQuery: Option[String],
      dropped: Int,
  )

  def parseJson(source: String, bytes: Array[Byte]): Either[Decode, Json] =
    io.circe.parser
      .parse(new String(bytes, StandardCharsets.UTF_8))
      .left
      .map(f => Decode(source, "", s"not JSON: ${f.message}"))

  /** Price field decoder: number | numeric string | null/absent -> Option[Money].
    * A non-numeric non-null value is a field-level Decode error naming the pointer.
    */
  def priceValue(source: String, pointer: String, value: Option[Json]): Either[Decode, Option[Money]] =
    value match {
      case None                => Right(None)
      case Some(j) if j.isNull => Right(None)
      // An EMPTY string means the same thing null does: this item carries no
      // price, with the offer living in the name or artwork instead (01.4).
      // Treating it as a bad price dropped 142 of 485 items in a single real
      // flyer — roughly 14% of everything the first live run fetched.
      case Some(j) if j.asString.exists(_.trim.isEmpty) => Right(None)
      case Some(j) =>
        val parsed = j.asNumber
          .map(n => Money.fromBigDecimal(n.toBigDecimal.getOrElse(BigDecimal(n.toDouble))))
          .orElse(j.asString.map(Money.fromDecimal(_)))
        parsed match {
          case Some(Right(m)) => Right(Some(m))
          case Some(Left(e))  => Left(Decode(source, pointer, s"bad price: $e"))
          case None           => Left(Decode(source, pointer, "price is neither number, numeric string, nor null"))
        }
    }

  /** ISO-8601 offset datetime -> Instant. */
  def instantValue(source: String, pointer: String, value: Json): Either[Decode, Instant] =
    value.asString
      .toRight(Decode(source, pointer, "timestamp is not a string"))
      .flatMap(s =>
        Try(OffsetDateTime.parse(s).toInstant).toOption
          .toRight(Decode(source, pointer, s"malformed timestamp: $s"))
      )

  private def str(source: String, c: ACursor, field: String, pointer: String): Either[Decode, String] =
    c.downField(field).focus.flatMap(_.asString).filter(_.nonEmpty) match {
      case Some(s) => Right(s)
      case None    => Left(Decode(source, s"$pointer.$field", "missing or empty string"))
    }

  private def long(source: String, c: ACursor, field: String, pointer: String): Either[Decode, Long] =
    c.downField(field).focus.flatMap(_.asNumber).flatMap(_.toLong) match {
      case Some(n) => Right(n)
      case None    => Left(Decode(source, s"$pointer.$field", "missing or non-integer"))
    }

  private def instantAt(source: String, c: ACursor, field: String, pointer: String): Either[Decode, Instant] =
    c.downField(field).focus match {
      case Some(j) => instantValue(source, s"$pointer.$field", j)
      case None    => Left(Decode(source, s"$pointer.$field", "missing timestamp"))
    }

  private def optStr(c: ACursor, field: String): Option[String] =
    c.downField(field).focus.flatMap(_.asString).map(_.trim).filter(_.nonEmpty)

  private def localeOf(source: String, s: String, pointer: String): Either[Decode, Locale] = {
    val lower = s.toLowerCase
    if (lower.startsWith("fr")) Right(Locale.FrCa)
    else if (lower.startsWith("en")) Right(Locale.EnCa)
    else Left(Decode(source, pointer, s"unrecognized locale: $s"))
  }

  /** One flyer object of the flyers listing (01.2). */
  def decodeFlyer(source: String, c: ACursor, pointer: String): Either[Decode, (Flyer, Merchant)] =
    for {
      id         <- long(source, c, "id", pointer).map(FlyerId(_))
      merchantId <- long(source, c, "merchant_id", pointer).map(n => MerchantId(n.toInt))
      merchant   <- str(source, c, "merchant", pointer)
      name       <- str(source, c, "name", pointer)
      from       <- instantAt(source, c, "valid_from", pointer)
      to         <- instantAt(source, c, "valid_to", pointer)
      postalRaw  <- str(source, c, "postal_code", pointer)
      postal <- PostalCode
        .parse(postalRaw)
        .left
        .map(e => Decode(source, s"$pointer.postal_code", s"bad postal code: $e"))
      localeRaw <- str(source, c, "locale", pointer)
      locale    <- localeOf(source, localeRaw, s"$pointer.locale")
      flyer <- Flyer
        .of(id, merchantId, name, from, to, postal, locale)
        .left
        .map(e => Decode(source, pointer, e.reason))
    } yield (flyer, Merchant(merchantId, merchant))

  /** Top-level flyers listing envelope; individual bad flyers are dropped and counted. */
  def decodeListing(source: String, json: Json): Either[Decode, ListingParse] =
    json.hcursor.downField("flyers").values match {
      case None => Left(Decode(source, "flyers", "missing flyers array"))
      case Some(values) =>
        val results = values.toList.zipWithIndex.map { case (j, i) =>
          decodeFlyer(source, j.hcursor, s"flyers[$i]")
        }
        val flyers    = results.collect { case Right((f, _)) => f }
        val merchants = results.collect { case Right((_, m)) => m }.distinct
        Right(ListingParse(flyers, merchants, dropped = results.count(_.isLeft)))
    }

  /** The merchant of an item that did not carry one. Per-flyer responses omit
    * merchant entirely — it is a property of the flyer — so the orchestrator
    * resolves it from the flyer it already holds (01.4's stated contract).
    */
  val UnresolvedMerchant: MerchantId = MerchantId(0)

  /** One flyer item (01.4/01.3). A null price is expected, never an error; an
    * inverted validity window or missing name is a Decode rejection of the item.
    *
    * The two endpoints return DIFFERENT item shapes, verified live 2026-08-20:
    *
    *   items/search  -> flyer_item_id, current_price, original_price, sale_story, merchant_id
    *   flyers/{id}   -> id, price (a string), discount (an int), and NO merchant
    *
    * 01.4's field table described the search shape, which is why this decoder
    * originally required `merchant_id` and `current_price` and therefore dropped
    * every single item of every real per-flyer response.
    */
  def decodeItem(source: String, c: ACursor, pointer: String): Either[Decode, FlyerItem] =
    for {
      rawName <- str(source, c, "name", pointer)
      sourceItemId <- long(source, c, "flyer_item_id", pointer)
        .orElse(long(source, c, "id", pointer))
        .map(_.toString)
      flyerId <- long(source, c, "flyer_id", pointer).map(FlyerId(_))
      // absent on the per-flyer endpoint; the orchestrator fills it from the flyer
      merchantId = long(source, c, "merchant_id", pointer).map(n => MerchantId(n.toInt)).getOrElse(UnresolvedMerchant)
      current <- priceValue(source, s"$pointer.current_price", c.downField("current_price").focus)
        .flatMap {
          case some @ Some(_) => Right(some)
          case None           => priceValue(source, s"$pointer.price", c.downField("price").focus)
        }
      original <- priceValue(source, s"$pointer.original_price", c.downField("original_price").focus)
      from       <- instantAt(source, c, "valid_from", pointer)
      to         <- instantAt(source, c, "valid_to", pointer)
      _ <-
        if (from.isBefore(to)) Right(())
        else Left(Decode(source, pointer, s"non-positive validity window: $from >= $to"))
    } yield {
      // pre/post price text folds into the sale story (01.4); rawName is verbatim,
      // the bilingual split is normalization's job (02.5), so name stays empty here.
      // `discount` (per-flyer only) is an integer whose units are NOT documented
      // and were not verifiable from the response alone. It is preserved as
      // opaque sale text — the same treatment "25 points" gets — rather than
      // being read as a percentage and used to fabricate an original price,
      // which would silently corrupt discount gating (05.1) and verdicts (07.3).
      val discount  = c.downField("discount").focus.flatMap(_.asNumber).map(n => s"discount ${n.toString}")
      val saleStory =
        List(optStr(c, "pre_price_text"), optStr(c, "sale_story"), optStr(c, "post_price_text"), discount).flatten
      FlyerItem(
        sourceItemId = sourceItemId,
        flyerId = flyerId,
        merchantId = merchantId,
        name = BilingualText.empty,
        rawName = rawName,
        currentPrice = current,
        originalPrice = original,
        saleStory = if (saleStory.isEmpty) None else Some(saleStory.mkString(" ")),
        validFrom = from,
        validTo = to,
      )
    }

  private def decodeItemArray(source: String, json: Json, field: String): (List[FlyerItem], List[Merchant], Int) =
    json.hcursor.downField(field).values match {
      case None => (Nil, Nil, 0)
      case Some(values) =>
        val indexed = values.toList.zipWithIndex
        val results = indexed.map { case (j, i) => (decodeItem(source, j.hcursor, s"$field[$i]"), j) }
        val items   = results.collect { case (Right(item), _) => item }
        val merchants = results.collect {
          case (Right(item), j) =>
            j.hcursor.downField("merchant_name").focus.flatMap(_.asString).map(Merchant(item.merchantId, _))
        }.flatten.distinct
        (items, merchants, results.count(_._1.isLeft))
    }

  /** Per-flyer items envelope (01.4). `pages` (flyer imagery) is never decoded or retained. */
  def decodeItems(source: String, json: Json): Either[Decode, ItemsParse] =
    json.hcursor.downField("items").values match {
      case None => Left(Decode(source, "items", "missing items array"))
      case Some(_) =>
        val (items, _, dropped) = decodeItemArray(source, json, "items")
        Right(ItemsParse(items, dropped))
    }

  private def decodeEcomItem(source: String, c: ACursor, pointer: String): Either[Decode, EcomItem] =
    for {
      rawName    <- str(source, c, "name", pointer)
      merchantId <- long(source, c, "merchant_id", pointer).map(n => MerchantId(n.toInt))
      merchant   <- str(source, c, "merchant", pointer)
      itemId <- str(source, c, "item_id", pointer)
        .orElse(str(source, c, "global_id", pointer))
        .orElse(long(source, c, "id", pointer).map(_.toString))
      current  <- priceValue(source, s"$pointer.current_price", c.downField("current_price").focus)
      original <- priceValue(source, s"$pointer.original_price", c.downField("original_price").focus)
    } yield EcomItem(itemId, merchantId, merchant, rawName, current, original)

  /** Search envelope (01.3): the two item arrays are decoded separately and never conflated. */
  def decodeSearch(source: String, json: Json): Either[Decode, SearchParse] =
    json.hcursor.downField("items").values match {
      case None => Left(Decode(source, "items", "missing items array"))
      case Some(_) =>
        val (flyerItems, merchants, droppedFlyer) = decodeItemArray(source, json, "items")
        val ecomResults = json.hcursor.downField("ecom_items").values.toList.flatMap(_.toList).zipWithIndex.map {
          case (j, i) => decodeEcomItem(source, j.hcursor, s"ecom_items[$i]")
        }
        val ecomItems = ecomResults.collect { case Right(e) => e }
        Right(
          SearchParse(
            flyerItems = flyerItems,
            ecomItems = ecomItems,
            merchants = merchants,
            normalizedQuery = json.hcursor.downField("normalized_query").focus.flatMap(_.asString),
            dropped = droppedFlyer + ecomResults.count(_.isLeft),
          )
        )
    }
}
