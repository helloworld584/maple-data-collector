# Maple Data Collector

메이플스토리 경매장 거래 내역 자동 수집 앱

## 개요
메이플핸즈+ 앱 실행 중 화면 위에 오버레이 버튼을 띄워,
버튼 클릭 시 OCR로 거래 내역을 자동 수집하여 Supabase에 저장하는 Android 앱.

## 목적
Item Valuation AI 프로젝트의 메이플스토리 가격 데이터 수집 파이프라인.
개인 학습 및 연구 목적으로 제작됨.

## 기술 스택
- Android (Kotlin)
- Overlay (SYSTEM_ALERT_WINDOW)
- MediaProjection API (화면 캡처)
- Google Vision API (OCR)
- Supabase (데이터 저장)

## 주의
.env 및 API 키는 절대 커밋하지 않음.
