package com.fauxx.ui.format

import androidx.annotation.StringRes
import com.fauxx.R
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.Gender
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.Region

/**
 * Maps the four onboarding demographic enums to localized display strings.
 *
 * Internally fauxx persists `Profession.ENGINEER` / `Region.SPAIN` / etc. by enum name
 * so the data is locale-independent on disk and on the wire (synthetic activity
 * dispatch). This file is the only place display labels are resolved — the UI never
 * calls `enum.name` directly.
 *
 * When extending an enum (notably [Region], which grows whenever a new locale ships):
 *  1. Add the enum value to [com.fauxx.targeting.layer1.UserDemographicProfile].
 *  2. Add `region_<lowercase_name>` to `values/strings.xml` and every shipped
 *     `values-<locale>/strings.xml`.
 *  3. Add the case to [Region.displayNameRes] below — the `when` is exhaustive on
 *     the enum, so the compiler will complain until the case is filled.
 */

@StringRes
fun AgeRange.displayNameRes(): Int = when (this) {
    AgeRange.AGE_18_24 -> R.string.age_18_24
    AgeRange.AGE_25_34 -> R.string.age_25_34
    AgeRange.AGE_35_44 -> R.string.age_35_44
    AgeRange.AGE_45_54 -> R.string.age_45_54
    AgeRange.AGE_55_64 -> R.string.age_55_64
    AgeRange.AGE_65_PLUS -> R.string.age_65_plus
}

@StringRes
fun Gender.displayNameRes(): Int = when (this) {
    Gender.MALE -> R.string.gender_male
    Gender.FEMALE -> R.string.gender_female
    Gender.PREFER_NOT_TO_SAY -> R.string.gender_prefer_not_to_say
}

@StringRes
fun Profession.displayNameRes(): Int = when (this) {
    Profession.STUDENT -> R.string.profession_student
    Profession.TEACHER -> R.string.profession_teacher
    Profession.ENGINEER -> R.string.profession_engineer
    Profession.HEALTHCARE -> R.string.profession_healthcare
    Profession.LEGAL -> R.string.profession_legal
    Profession.FINANCE_PROF -> R.string.profession_finance_prof
    Profession.RETAIL -> R.string.profession_retail
    Profession.TRADES -> R.string.profession_trades
    Profession.CREATIVE -> R.string.profession_creative
    Profession.RETIRED -> R.string.profession_retired
    Profession.HOMEMAKER -> R.string.profession_homemaker
    Profession.OTHER -> R.string.profession_other
}

@StringRes
fun Region.displayNameRes(): Int = when (this) {
    Region.US_NORTHEAST -> R.string.region_us_northeast
    Region.US_SOUTHEAST -> R.string.region_us_southeast
    Region.US_MIDWEST -> R.string.region_us_midwest
    Region.US_SOUTHWEST -> R.string.region_us_southwest
    Region.US_WEST -> R.string.region_us_west
    Region.CANADA -> R.string.region_canada
    Region.UK -> R.string.region_uk
    Region.WESTERN_EUROPE -> R.string.region_western_europe
    Region.EASTERN_EUROPE -> R.string.region_eastern_europe
    Region.ASIA_PACIFIC -> R.string.region_asia_pacific
    Region.LATIN_AMERICA -> R.string.region_latin_america
    Region.MIDDLE_EAST_AFRICA -> R.string.region_middle_east_africa
    Region.SPAIN -> R.string.region_spain
    Region.MEXICO -> R.string.region_mexico
    Region.ARGENTINA -> R.string.region_argentina
    Region.COLOMBIA -> R.string.region_colombia
    Region.CHILE -> R.string.region_chile
    Region.PERU -> R.string.region_peru
    Region.FRANCE -> R.string.region_france
    Region.QUEBEC -> R.string.region_quebec
    Region.BELGIUM -> R.string.region_belgium
    Region.SWITZERLAND -> R.string.region_switzerland
    Region.OTHER -> R.string.region_other
}
