package com.iseohyun.mylore.sync.data.remote

import com.google.gson.annotations.SerializedName
import com.iseohyun.mylore.sync.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * 카카오 로컬 API (좌표 → 주소, 좌표 주변 장소 검색).
 * GPS 체류 지점에 붙일 장소 이름 후보를 얻는 데만 사용하며, 사용자가 조회를 누르고 동의한 경우에만 호출합니다.
 * 키는 `local.properties`의 `KAKAO_REST_API_KEY`에서 읽습니다.
 */
interface KakaoLocalApi {

    @GET("v2/local/geo/coord2address.json")
    suspend fun coordToAddress(
        @Header("Authorization") authorization: String,
        @Query("x") longitude: Double,
        @Query("y") latitude: Double
    ): Response<KakaoAddressResponse>

    @GET("v2/local/search/category.json")
    suspend fun searchByCategory(
        @Header("Authorization") authorization: String,
        @Query("category_group_code") categoryGroupCode: String,
        @Query("x") longitude: Double,
        @Query("y") latitude: Double,
        @Query("radius") radiusMeters: Int,
        @Query("sort") sort: String = "distance",
        @Query("size") size: Int = 3
    ): Response<KakaoPlaceResponse>

    @GET("v2/local/search/keyword.json")
    suspend fun searchByKeyword(
        @Header("Authorization") authorization: String,
        @Query("query") query: String,
        @Query("x") longitude: Double,
        @Query("y") latitude: Double,
        @Query("radius") radiusMeters: Int,
        @Query("sort") sort: String = "distance",
        @Query("size") size: Int = 10
    ): Response<KakaoPlaceResponse>
}

data class KakaoAddressResponse(
    @SerializedName("documents") val documents: List<KakaoAddressDocument> = emptyList()
)

data class KakaoAddressDocument(
    @SerializedName("road_address") val roadAddress: KakaoRoadAddress? = null,
    @SerializedName("address") val address: KakaoJibunAddress? = null
)

data class KakaoRoadAddress(
    @SerializedName("address_name") val addressName: String = "",
    @SerializedName("building_name") val buildingName: String = ""
)

data class KakaoJibunAddress(
    @SerializedName("address_name") val addressName: String = ""
)

data class KakaoPlaceResponse(
    @SerializedName("documents") val documents: List<KakaoPlaceDocument> = emptyList()
)

data class KakaoPlaceDocument(
    @SerializedName("place_name") val placeName: String = "",
    @SerializedName("category_name") val categoryName: String = "",
    @SerializedName("distance") val distance: String = ""
)

/** 장소 이름 후보 한 건: [name]을 누르면 이름 입력란에 채워지고, [detail]은 근거(종류·거리)를 보여 줍니다. */
data class PlaceCandidate(
    val name: String,
    val detail: String
)

data class PlaceLookupResult(
    val candidates: List<PlaceCandidate> = emptyList(),
    val errorMessage: String? = null
)

object KakaoPlaceLookup {

    private const val BASE_URL = "https://dapi.kakao.com/"

    /** 체류 지점 주변에서 장소 후보를 찾는 반경(m) */
    private const val NEARBY_RADIUS_METERS = 80

    /**
     * "이 주소에 있는 장소" 검색 반경(m).
     * 좌표의 주소 문자열(예: "…모전리 676")을 검색어로 넣으면 그 번지에 등록된 장소가 분류와 무관하게 나옵니다.
     * 분류 검색에 잡히지 않는 수영장·체육시설 등을 찾는 핵심 방법입니다.
     */
    private const val ADDRESS_PLACE_RADIUS_METERS = 200

    /** 주변 검색에 쓰는 카카오 분류 코드: 문화시설·관광명소·공공기관·학교·병원·대형마트·음식점·카페 */
    private val NEARBY_CATEGORY_CODES = listOf("CT1", "AT4", "PO3", "SC4", "HP8", "MT1", "FD6", "CE7")

    private const val MAX_NEARBY_CANDIDATES = 8

    /**
     * 검색어로 주변을 찾을 때의 반경(m).
     * 수영장·체육시설처럼 카카오 분류 코드가 없는 장소는 분류 검색에 나오지 않으므로,
     * 사용자가 적은 검색어(예: "수영장")로 한 번 더 찾습니다.
     */
    private const val KEYWORD_RADIUS_METERS = 500

    val isConfigured: Boolean
        get() = BuildConfig.KAKAO_REST_API_KEY.isNotBlank()

    private val api: KakaoLocalApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KakaoLocalApi::class.java)
    }

    /**
     * 좌표의 건물명·주소와 주변 장소를 조회해 이름 후보를 만듭니다.
     * 순서: 검색어로 찾은 장소 → 이 주소에 등록된 장소 → 건물명 → 80m 이내 주변 장소 → 도로명 주소 → 지번 주소
     *
     * @param keyword 사용자가 이름 칸에 적어 둔 말(예: "수영장"). 비어 있지 않으면 이 말로 주변을 먼저 검색합니다.
     */
    suspend fun lookup(
        latitude: Double,
        longitude: Double,
        keyword: String = ""
    ): PlaceLookupResult = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext PlaceLookupResult(errorMessage = "카카오 키가 설정되지 않았습니다. (local.properties의 KAKAO_REST_API_KEY)")
        }
        val auth = "KakaoAK ${BuildConfig.KAKAO_REST_API_KEY}"

        try {
            val addressRes = api.coordToAddress(auth, longitude, latitude)
            if (!addressRes.isSuccessful) {
                return@withContext PlaceLookupResult(errorMessage = describeHttpError(addressRes.code()))
            }

            val candidates = ArrayList<PlaceCandidate>()

            val trimmedKeyword = keyword.trim()
            if (trimmedKeyword.isNotEmpty()) {
                try {
                    api.searchByKeyword(auth, trimmedKeyword, longitude, latitude, KEYWORD_RADIUS_METERS)
                        .body()?.documents.orEmpty()
                        .filter { it.placeName.isNotBlank() }
                        .forEach { place ->
                            val distanceText = place.distance.toIntOrNull()?.let { " · ${it}m" }.orEmpty()
                            candidates.add(PlaceCandidate(place.placeName, "'$trimmedKeyword' 검색$distanceText"))
                        }
                } catch (_: Exception) {
                    // 검색어 조회가 실패해도 주소·주변 장소 후보는 계속 보여 줌
                }
            }

            val doc = addressRes.body()?.documents?.firstOrNull()

            // 이 좌표의 주소(도로명·지번)에 등록된 장소를 가까운 순으로 후보에 추가
            val addressQueries = listOfNotNull(
                doc?.roadAddress?.addressName?.takeIf { it.isNotBlank() },
                doc?.address?.addressName?.takeIf { it.isNotBlank() }
            ).distinct()
            for (addressQuery in addressQueries) {
                try {
                    api.searchByKeyword(auth, addressQuery, longitude, latitude, ADDRESS_PLACE_RADIUS_METERS)
                        .body()?.documents.orEmpty()
                        .filter { it.placeName.isNotBlank() }
                        .forEach { place ->
                            val kind = place.categoryName.substringAfterLast(" > ").ifBlank { "이 주소의 장소" }
                            val distanceText = place.distance.toIntOrNull()?.let { " · ${it}m" }.orEmpty()
                            candidates.add(PlaceCandidate(place.placeName, kind + distanceText))
                        }
                } catch (_: Exception) {
                    // 실패해도 나머지 후보는 계속 보여 줌
                }
            }

            val buildingName = doc?.roadAddress?.buildingName.orEmpty().trim()
            if (buildingName.isNotEmpty()) {
                candidates.add(PlaceCandidate(buildingName, "건물명"))
            }

            // 분류별 주변 검색을 동시에 요청하고, 일부가 실패해도 나머지 결과는 사용
            val nearby = coroutineScope {
                NEARBY_CATEGORY_CODES.map { code ->
                    async {
                        try {
                            api.searchByCategory(auth, code, longitude, latitude, NEARBY_RADIUS_METERS)
                                .body()?.documents.orEmpty()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            nearby
                .filter { it.placeName.isNotBlank() }
                .sortedBy { it.distance.toIntOrNull() ?: Int.MAX_VALUE }
                .distinctBy { it.placeName }
                .take(MAX_NEARBY_CANDIDATES)
                .forEach { place ->
                    val kind = place.categoryName.substringAfterLast(" > ").ifBlank { "주변 장소" }
                    val distanceText = place.distance.toIntOrNull()?.let { " · ${it}m" }.orEmpty()
                    candidates.add(PlaceCandidate(place.placeName, kind + distanceText))
                }

            doc?.roadAddress?.addressName?.takeIf { it.isNotBlank() }?.let {
                candidates.add(PlaceCandidate(it, "도로명 주소"))
            }
            doc?.address?.addressName?.takeIf { it.isNotBlank() }?.let {
                candidates.add(PlaceCandidate(it, "지번 주소"))
            }

            val distinct = candidates.distinctBy { it.name }
            if (distinct.isEmpty()) {
                PlaceLookupResult(errorMessage = "이 위치에서 찾은 주소나 장소가 없습니다.")
            } else {
                PlaceLookupResult(candidates = distinct)
            }
        } catch (e: Exception) {
            PlaceLookupResult(errorMessage = "조회에 실패했습니다: ${e.message ?: "네트워크 오류"}")
        }
    }

    private fun describeHttpError(code: Int): String = when (code) {
        401 -> "카카오 키가 올바르지 않습니다. (REST API 키인지 확인해 주세요)"
        403 -> "카카오 개발자 콘솔에서 이 앱의 '카카오맵' 사용 설정을 켜야 합니다."
        429 -> "카카오 조회 한도를 넘었습니다. 잠시 후 다시 시도해 주세요."
        else -> "카카오 조회가 거부되었습니다. (HTTP $code)"
    }
}
