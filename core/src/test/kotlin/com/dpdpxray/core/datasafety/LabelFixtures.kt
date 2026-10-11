package com.dpdpxray.core.datasafety

/**
 * Data safety texts written by the TraceVeil team for tests, in the shapes a person gets when copying the "Data safety"
 * part of a Google Play listing (English). They are not copied from any real app's label. The stock phrases ("No data
 * shared with third parties", "Data shared", "Data collected", "Data this app may collect", "Security practices", "Data is
 * encrypted in transit", "You can request that data be deleted", the "· Optional" suffix) are the generic Play UI strings
 * read on play.google.com/store/apps/datasafety on 11 Oct 2026. "This app may share these data types with third parties",
 * "This app may collect these data types" and "X, Y and 4 others" follow listing pages seen in web search results the
 * same day. "Data isn't encrypted" and "Data can't be deleted" are the negative forms as people quote them; they were not
 * read on a live page, so the parser accepts several spellings.
 */
object LabelFixtures {
    /** Listing summary: nothing shared, three categories collected, both security lines. */
    const val SUMMARY_NO_SHARING = """
Data safety
No data shared with third parties
Learn more about how developers declare sharing
This app may collect these data types
Location, App activity and Device or other IDs
Data is encrypted in transit
You can request that data be deleted
See details
"""

    /** Listing summary whose lists are cut short ("and 2 others"), with the negative security lines. */
    const val SUMMARY_TRUNCATED = """
This app may share these data types with third parties
Location, App activity and 2 others
This app may collect these data types
Location, Personal info and 5 others
Data isn't encrypted
Data can't be deleted
"""

    /** The "See details" page: category, then type (optionally "· Optional"), then purposes. */
    const val DETAILS_PAGE = """
Data safety
Data shared
Data that may be shared with other companies or organizations
Location
Approximate location
Advertising or marketing
Device or other IDs
Device or other IDs
Advertising or marketing, Analytics
Data shared and for what purpose
Data collected
Data this app may collect
Personal info
Name · Optional
App functionality, Account management
App activity
App interactions
Analytics
App info and performance
Crash logs
Diagnostics
Analytics
Device or other IDs
Device or other IDs
Analytics, Fraud prevention, security, and compliance
Security practices
Data is encrypted in transit
You can request that data be deleted
"""

    /** A label that declares nothing (the "none" shape, with the developer-says sentences). */
    const val NOTHING = """
Data safety
No data shared with third parties
The developer says this app doesn't share user data with other companies or organizations
No data collected
The developer says this app doesn't collect user data
Security practices
Data is encrypted in transit
"""

    /** Everything on one line, with an Oxford comma, as a person might type it from memory. */
    const val ONE_LINE = "This app may share these data types with third parties: App activity, App info and performance, and Device " +
        "or other IDs. This app may collect these data types: App activity, App info and performance, and Device or other IDs."

    /** Text with no Data safety statement at all. */
    const val NOT_A_LABEL = "Great app, five stars. Works offline and the shop is fast."
}
