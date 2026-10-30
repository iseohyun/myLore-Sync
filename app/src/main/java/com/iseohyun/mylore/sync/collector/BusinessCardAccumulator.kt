package com.iseohyun.mylore.sync.collector

import com.iseohyun.mylore.sync.data.model.BusinessCardInfo

/**
 * 실시간 연속 프레임 및 재스캔 시 이미 잘 인식된 데이터를 보존하면서,
 * 새로 감지된 신뢰도 높은 정보만을 점진적으로 누적/병합하는 지능형 엔진.
 */
class BusinessCardAccumulator {

    // 각 필드별 감지 빈도 (다수결 투표용)
    private val candidateHitCount = mutableMapOf<String, Int>()

    /**
     * 실시간 프레임에서 들어온 후보 텍스트를 누적 등록하고,
     * 충분한 프레임(예: 2~3회 이상)에서 일관되게 감지되었는지 점수를 부여합니다.
     */
    fun recordFrameCandidate(key: String, value: String) {
        if (value.isBlank()) return
        val entryKey = "$key:$value"
        candidateHitCount[entryKey] = (candidateHitCount[entryKey] ?: 0) + 1
    }

    fun getHitCount(key: String, value: String): Int {
        return candidateHitCount["$key:$value"] ?: 0
    }

    /**
     * 기존 명함 정보(base)와 새로운 스캔/프레임 정보(incoming)를
     * 신뢰도 점수 및 안전 보존 규칙에 따라 지능적으로 병합합니다.
     *
     * @param base 현재까지 확정된 명함 정보
     * @param incoming 새로운 프레임/스캔에서 추출된 정보
     * @param userEditedFields 사용자가 직접 수동 입력/수정한 필드 (절대 덮어쓰지 않음)
     */
    fun merge(
        base: BusinessCardInfo,
        incoming: BusinessCardInfo,
        userEditedFields: Set<String> = emptySet()
    ): BusinessCardInfo {
        var name = base.name
        var mobile = base.mobile
        var telephone = base.telephone
        var company = base.company
        var jobTitle = base.jobTitle
        var email = base.email
        var address = base.address
        var website = base.website
        val memoList = mutableListOf<String>()

        if (base.memo.isNotBlank()) {
            memoList.add(base.memo)
        }

        // 1. 이름 (사용자 편집 안 된 경우)
        if (!userEditedFields.contains("name")) {
            if (name.isBlank() && incoming.name.isNotBlank()) {
                name = incoming.name
            } else if (incoming.name.isNotBlank() && incoming.name != name) {
                // 한글 3자 완성형이 영문이나 불완전한 이름보다 우선
                val baseScore = scoreName(name)
                val inScore = scoreName(incoming.name)
                if (inScore > baseScore) {
                    name = incoming.name
                }
            }
        }

        // 2. 휴대폰 (010)
        if (!userEditedFields.contains("mobile")) {
            if (mobile.isBlank() && incoming.mobile.isNotBlank()) {
                mobile = incoming.mobile
            } else if (incoming.mobile.isNotBlank() && incoming.mobile != mobile) {
                val baseScore = scorePhone(mobile)
                val inScore = scorePhone(incoming.mobile)
                if (inScore > baseScore) {
                    // 기존 번호가 유효하다면 일반 전화나 메모로 백업
                    if (telephone.isBlank() && !mobile.startsWith("010")) {
                        telephone = mobile
                    }
                    mobile = incoming.mobile
                } else if (telephone.isBlank()) {
                    telephone = incoming.mobile
                }
            }
        }

        // 3. 일반/회사 전화
        if (!userEditedFields.contains("telephone")) {
            if (telephone.isBlank() && incoming.telephone.isNotBlank() && incoming.telephone != mobile) {
                telephone = incoming.telephone
            } else if (incoming.telephone.isNotBlank() && incoming.telephone != telephone) {
                if (scorePhone(incoming.telephone) > scorePhone(telephone)) {
                    telephone = incoming.telephone
                }
            }
        }

        // 4. 회사명
        if (!userEditedFields.contains("company")) {
            if (company.isBlank() && incoming.company.isNotBlank()) {
                company = incoming.company
            } else if (incoming.company.isNotBlank() && incoming.company != company) {
                // (주) 나 주식회사 등이 포함된 더 완전한 회사명을 우선
                if (scoreCompany(incoming.company) > scoreCompany(company)) {
                    company = incoming.company
                }
            }
        }

        // 5. 직책
        if (!userEditedFields.contains("jobTitle")) {
            if (jobTitle.isBlank() && incoming.jobTitle.isNotBlank()) {
                jobTitle = incoming.jobTitle
            } else if (incoming.jobTitle.isNotBlank() && incoming.jobTitle.length > jobTitle.length) {
                jobTitle = incoming.jobTitle
            }
        }

        // 6. 이메일
        if (!userEditedFields.contains("email")) {
            if (email.isBlank() && incoming.email.isNotBlank()) {
                email = incoming.email
            } else if (incoming.email.isNotBlank() && incoming.email != email) {
                if (scoreEmail(incoming.email) > scoreEmail(email)) {
                    email = incoming.email
                }
            }
        }

        // 7. 주소
        if (!userEditedFields.contains("address")) {
            if (address.isBlank() && incoming.address.isNotBlank()) {
                address = incoming.address
            } else if (incoming.address.isNotBlank() && incoming.address.length > address.length) {
                address = incoming.address
            }
        }

        // 8. 웹사이트
        if (!userEditedFields.contains("website")) {
            if (website.isBlank() && incoming.website.isNotBlank()) {
                website = incoming.website
            }
        }

        // 9. 메모 / 뒷면 정보 누적
        if (incoming.memo.isNotBlank() && !memoList.contains(incoming.memo)) {
            memoList.add(incoming.memo)
        }

        val frontUri = if (incoming.frontImageUri.isNotBlank()) incoming.frontImageUri else base.frontImageUri
        val backUri = if (incoming.backImageUri.isNotBlank()) incoming.backImageUri else base.backImageUri

        return base.copy(
            name = name,
            mobile = mobile,
            telephone = telephone,
            company = company,
            jobTitle = jobTitle,
            email = email,
            address = address,
            website = website,
            memo = memoList.joinToString("\n\n").trim(),
            frontImageUri = frontUri,
            backImageUri = backUri
        )
    }

    private fun scoreName(name: String): Int {
        var score = 0
        if (name.matches(Regex("^[가-힣]{3}$"))) score += 50
        else if (name.matches(Regex("^[가-힣]{2,4}$"))) score += 30
        if (!name.contains(" ") && name.length in 2..4) score += 20
        return score
    }

    private fun scorePhone(phone: String): Int {
        var score = 0
        val digits = phone.replace(Regex("[^0-9]"), "")
        if (digits.startsWith("010") && digits.length == 11) score += 50
        else if (digits.length in 9..12) score += 30
        if (phone.contains("-")) score += 10
        return score
    }

    private fun scoreCompany(company: String): Int {
        var score = company.length
        if (company.contains("주식회사") || company.contains("(주)") || company.contains("㈜")) score += 30
        if (company.contains("Corp") || company.contains("Inc") || company.contains("Ltd")) score += 20
        return score
    }

    private fun scoreEmail(email: String): Int {
        var score = 0
        if (email.contains("@") && email.contains(".")) score += 30
        if (email.endsWith(".com") || email.endsWith(".co.kr") || email.endsWith(".net") || email.endsWith(".kr")) score += 20
        return score
    }

    fun clear() {
        candidateHitCount.clear()
    }
}
