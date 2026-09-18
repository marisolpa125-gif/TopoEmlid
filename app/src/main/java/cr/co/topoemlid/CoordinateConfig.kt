package cr.co.topoemlid

object CoordinateCatalog {
    val profiles = listOf(
        CrsProfile("CRTM05", null, "Costa Rica Transverse Mercator 2005 — validar EPSG/parámetros oficiales antes de cálculo productivo"),
        CrsProfile("CR-SIRGAS", null, "Perfil SIRGAS para Costa Rica — seleccionar realización/parámetros oficiales del proyecto"),
        CrsProfile("WGS 84 geográficas", "EPSG:4326", "Latitud / longitud")
    )
}

interface GeoidService {
    fun undulationMeters(latitude: Double, longitude: Double): Double?
}
