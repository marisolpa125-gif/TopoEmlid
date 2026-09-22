package cr.co.topoemlid

object CoordinateCatalog {
    val profiles = listOf(
        CrsProfile(
            "CRTM05",
            "EPSG:5367",
            "CR05 / CRTM05 — coordenadas Este/Norte en metros."
        ),
        CrsProfile(
            "CR-SIRGAS",
            "EPSG:8908",
            "CR-SIRGAS época 2014.59 / CRTM05 — coordenadas Este/Norte en metros."
        ),
        CrsProfile(
            "WGS 84 geográficas",
            "EPSG:4326",
            "Latitud / longitud"
        )
    )

    fun byName(name: String): CrsProfile =
        profiles.firstOrNull { it.name == name } ?: profiles.last()
}

interface GeoidService {
    fun undulationMeters(latitude: Double, longitude: Double): Double?
}
