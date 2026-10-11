package com.dpdpxray.core.netcapture

import kotlinx.serialization.Serializable

/** The kind of personal data a cleartext request field looks like it carries. Saved by name: add values at the end only. */
@Serializable
enum class PersonalDataKind(val label: String) {
    EMAIL("email address"),
    PHONE("phone number"),
    DEVICE_ID("device identifier"),
    ADVERTISING_ID("advertising ID"),
    LOCATION("location"),
    NAME("person's name"),
    DATE_OF_BIRTH("date of birth"),
    ADDRESS("postal address"),
    GOVERNMENT_ID("government ID"),
    CREDENTIAL("password or one-time code"),
    FINANCIAL("payment card or account detail"),
}

/**
 * Field names that usually carry personal data (email, phone, imei, android_id, gaid/adid, lat/lon, first_name, …),
 * matched across naming styles (userEmail, e_mail, EMAIL_ADDRESS). Deliberately conservative, because a match can feed a
 * High finding: bare "name" (usually an event or app name), "id", "long", and look-alikes such as "phone_model" or
 * "advertiser_id" do not count.
 */
object PersonalFieldNames {
    private val names: Map<PersonalDataKind, Set<String>> = mapOf(
        PersonalDataKind.EMAIL to setOf("email", "emailaddress", "emailaddr", "emailid", "mail", "mailid", "useremail"),
        PersonalDataKind.PHONE to setOf(
            "phone", "phoneno", "phonenumber", "phonenum", "mobile", "mobileno", "mobilenumber", "mobilenum", "msisdn", "tel",
            "telephone", "contactnumber", "contactno", "cellphone", "whatsappnumber",
        ),
        PersonalDataKind.DEVICE_ID to setOf(
            "imei", "imei1", "imei2", "meid", "imsi", "iccid", "androidid", "deviceid", "deviceserial", "serial", "serialno",
            "serialnumber", "macaddress", "macaddr", "mac", "wifimac", "bluetoothmac", "btmac", "udid", "hardwareid", "hwid", "ssaid",
        ),
        PersonalDataKind.ADVERTISING_ID to setOf(
            "gaid", "adid", "aaid", "advertisingid", "adsid", "idfa", "ifa", "googleadid", "googleadvertisingid", "gpsadid",
            "advertisingidentifier", "adtrackingid", "maid",
        ),
        PersonalDataKind.LOCATION to setOf(
            "lat", "lon", "lng", "latitude", "longitude", "location", "geolocation", "geo", "latlng", "latlon", "latlong",
            "coords", "coordinates", "gps", "gpslocation", "userlocation", "currentlocation",
        ),
        PersonalDataKind.NAME to setOf(
            "firstname", "lastname", "fullname", "fname", "lname", "givenname", "familyname", "surname", "middlename",
            "username", "customername", "displayname", "nickname", "contactname", "personname", "realname", "membername",
        ),
        PersonalDataKind.DATE_OF_BIRTH to setOf("dob", "dateofbirth", "birthdate", "birthday", "birthdt", "bday", "yearofbirth", "birthyear"),
        PersonalDataKind.ADDRESS to setOf(
            "address", "homeaddress", "streetaddress", "street", "addressline", "addressline1", "addressline2", "address1",
            "address2", "pincode", "zipcode", "zip", "postcode", "postalcode",
        ),
        PersonalDataKind.GOVERNMENT_ID to setOf(
            "aadhaar", "aadhar", "aadhaarno", "aadhaarnumber", "aadharno", "aadharnumber", "pan", "panno", "pannumber",
            "pancard", "ssn", "passport", "passportno", "passportnumber", "voterid", "drivinglicence", "drivinglicense", "dlnumber",
        ),
        PersonalDataKind.CREDENTIAL to setOf("password", "passwd", "pwd", "passcode", "userpassword", "otp", "otpcode", "mpin", "upipin"),
        PersonalDataKind.FINANCIAL to setOf(
            "cardnumber", "cardno", "ccnumber", "creditcard", "debitcard", "cvv", "accountnumber", "accountno", "upiid", "vpa",
        ),
    )

    /** Words that make a "phone…" name about the handset rather than a number (phone_model, phoneBrand). */
    private val handsetWords = setOf("model", "brand", "type", "os", "make", "manufacturer", "carrier", "name", "version", "lang", "locale")
    private val SAFE = Regex("[A-Za-z0-9_.\\-\\[\\]]{1,40}")

    fun kindOf(name: String): PersonalDataKind? {
        val tokens = tokens(name)
        if (tokens.isEmpty()) return null
        val joined = tokens.joinToString("")
        names.entries.firstOrNull { joined in it.value }?.let { return it.key }
        if ("email" in tokens) return PersonalDataKind.EMAIL
        if (("phone" in tokens || "msisdn" in tokens) && tokens.none { it in handsetWords }) return PersonalDataKind.PHONE
        if (tokens.any { it == "imei" || it == "imsi" || it == "iccid" }) return PersonalDataKind.DEVICE_ID
        if ("latitude" in tokens || "longitude" in tokens) return PersonalDataKind.LOCATION
        return null
    }

    /** [name] as it may be stored and shown, or null when it has characters or a length outside a plain field name. */
    fun safe(name: String): String? = name.takeIf { SAFE.matches(it) }

    /** "userEmail" → [user, email]; "EMAIL_ADDRESS" → [email, address]; "items[0].sku" → [items, 0, sku]. */
    private fun tokens(name: String): List<String> =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.map { it.lowercase() }
}

/** One field of a cleartext request that looks like personal data. Only the field name and the kind are kept. */
@Serializable
data class PersonalHit(
    /** The query field name, "(path)" or "(header)". */
    val field: String,
    val kind: PersonalDataKind,
    /** true when the value itself had the shape of [kind] (seen by [Redaction]); false when only the field name matched. */
    val valueShape: Boolean,
)

/**
 * Personal-data-looking fields in one cleartext HTTP request, read from its already-redacted target and its
 * [HttpExchange.sensitive] kinds. Values are never available here: [Redaction] replaced them before storage.
 *
 * A value shape counts on its own only for e-mail addresses and phone numbers. A UUID, a 15-digit number or a coordinate
 * counts only under a field name of the matching kind (request IDs are UUIDs too). A matching field name with any other
 * non-empty value counts as name-only evidence.
 */
object CleartextPersonalData {
    const val PATH = "(path)"
    const val HEADER = "(header)"
    private val placeholder = Regex("\\[(email|phone|device-id|15-digit-id|coordinate|token|personal)]")

    fun scan(h: HttpExchange): List<PersonalHit> {
        val out = LinkedHashMap<Pair<String, PersonalDataKind>, PersonalHit>()
        fun add(hit: PersonalHit) {
            val key = hit.field to hit.kind
            val old = out[key]
            if (old == null || (!old.valueShape && hit.valueShape)) out[key] = hit
        }
        val q = h.target.indexOf('?')
        val path = if (q < 0) h.target else h.target.substring(0, q)
        val shapesInPath = placeholder.findAll(path).map { it.groupValues[1] }.toSet()
        if ("email" in shapesInPath) add(PersonalHit(PATH, PersonalDataKind.EMAIL, true))
        if ("phone" in shapesInPath) add(PersonalHit(PATH, PersonalDataKind.PHONE, true))
        if (q >= 0) {
            for (kv in h.target.substring(q + 1).split('&')) {
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                val name = decode(kv.substring(0, eq))
                val value = kv.substring(eq + 1)
                if (value.isEmpty()) continue // a field sent without a value carries nothing
                val field = PersonalFieldNames.safe(name) ?: "(field)"
                val shapes = placeholder.findAll(value).map { it.groupValues[1] }.toSet()
                val byName = PersonalFieldNames.kindOf(name)
                if ("email" in shapes) add(PersonalHit(field, PersonalDataKind.EMAIL, true))
                if ("phone" in shapes) add(PersonalHit(field, PersonalDataKind.PHONE, true))
                when {
                    byName == null -> {}
                    byName == PersonalDataKind.DEVICE_ID && ("device-id" in shapes || "15-digit-id" in shapes) -> add(PersonalHit(field, byName, true))
                    byName == PersonalDataKind.ADVERTISING_ID && "device-id" in shapes -> add(PersonalHit(field, byName, true))
                    byName == PersonalDataKind.LOCATION && "coordinate" in shapes -> add(PersonalHit(field, byName, true))
                    byName == PersonalDataKind.EMAIL && "email" in shapes -> {}
                    byName == PersonalDataKind.PHONE && "phone" in shapes -> {}
                    else -> add(PersonalHit(field, byName, false))
                }
            }
        }
        // E-mail addresses or phone numbers seen only in headers (the header name is not kept).
        val inTarget = out.values.map { it.kind }.toSet()
        if (SensitiveKind.EMAIL in h.sensitive && PersonalDataKind.EMAIL !in inTarget) add(PersonalHit(HEADER, PersonalDataKind.EMAIL, true))
        if (SensitiveKind.PHONE in h.sensitive && PersonalDataKind.PHONE !in inTarget) add(PersonalHit(HEADER, PersonalDataKind.PHONE, true))
        return out.values.toList()
    }

    /** "email (email address), first_name (person's name, field name only)". */
    fun describe(hits: List<PersonalHit>): String = hits.joinToString { h ->
        "${h.field} (${h.kind.label}${if (h.valueShape) "" else ", field name only"})"
    }

    private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
