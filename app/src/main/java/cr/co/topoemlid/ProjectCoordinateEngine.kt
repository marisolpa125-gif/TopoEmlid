package cr.co.topoemlid

import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate

data class ProjectCoordinate(
    val eastingM: Double?,
    val northingM: Double?,
    val latitude: Double,
    val longitude: Double
)

object ProjectCoordinateEngine {
    private val crsFactory = CRSFactory()
    private val transformFactory = CoordinateTransformFactory()

    private val wgs84 = crsFactory.createFromParameters(
        "WGS84",
        "+proj=longlat +datum=WGS84 +no_defs"
    )

    private val crSirgasCrtm05 = crsFactory.createFromParameters(
        "CR-SIRGAS / CRTM05",
        "+proj=tmerc +lat_0=0 +lon_0=-84 +k=0.9999 +x_0=500000 +y_0=0 +ellps=GRS80 +units=m +no_defs"
    )

    private val cr05Crtm05 = crsFactory.createFromParameters(
        "CR05 / CRTM05",
        "+proj=tmerc +lat_0=0 +lon_0=-84 +k=0.9999 +x_0=500000 +y_0=0 +ellps=WGS84 " +
            "+towgs84=-0.16959,0.35312,0.51846,-0.03385,0.16325,-0.03446,0.03693 +units=m +no_defs"
    )

    fun fromWgs84(latitude: Double, longitude: Double, crsName: String): ProjectCoordinate {
        if (crsName == "WGS 84 geográficas") {
            return ProjectCoordinate(null, null, latitude, longitude)
        }

        val target = when (crsName) {
            "CR-SIRGAS" -> crSirgasCrtm05
            "CRTM05" -> cr05Crtm05
            else -> crSirgasCrtm05
        }

        val src = ProjCoordinate(longitude, latitude)
        val dst = ProjCoordinate()
        transformFactory.createTransform(wgs84, target).transform(src, dst)
        return ProjectCoordinate(
            eastingM = dst.x,
            northingM = dst.y,
            latitude = latitude,
            longitude = longitude
        )
    }
}
