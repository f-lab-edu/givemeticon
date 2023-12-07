# 🎁 givemeticon

새로운 소비 트렌드를 반영하여 낭비를 최소화하는 **기프티콘 전문 중고거래 플랫폼**으로, 사용하지 않거나 만료가 임박한 기프티콘을 판매하고 저렴한 가격으로 구매할 수 있는 환경을 제공하는 서비스 플랫폼

## 🔧 사용 및 기술환경

---
<div>
    <img src="https://img.shields.io/badge/Java17-red?style=for-the-badge&logo=Java&logoColor=white"/> 
    <img src="https://img.shields.io/badge/spring boot-brightgreen?style=for-the-badge&logo=spring boot&logoColor=white"/>
    <img src="https://img.shields.io/badge/MyBatis-000000?style=for-the-badge&logo=MyBatis&logoColor=white">
    <img src="https://img.shields.io/badge/amazon_rds-%23%23527FFF?style=for-the-badge&logo=amazon_rds&logoColor=white"/>
    <img src="https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=Redis&logoColor=white"/>
<br/>
    <img src="https://img.shields.io/badge/docker-2496ED?style=for-the-badge&logo=docker&logoColor=white"/>
    <img src="https://img.shields.io/badge/amazon_ec2-%23FF9900?style=for-the-badge&logo=amazone_ec2&logoColor=white"/>
<br/>
    <img src="https://img.shields.io/badge/github_actions-%232088FF?style=for-the-badge&logo=github%20actions&logoColor=white"/>
</div>

## 📖 간단한 화면 구성도 (그림)

---

<div style="overflow-x: auto; white-space: nowrap">
    <img src="assets/로그인.png" width="200" height="350" alt="로그인">
    <img src="assets/브랜드선택.png" width="200" height="350"alt="브랜드 선택">
    <img src="assets/아이템선택.png" width="200" height="350" alt="아이템 선택">
    <img src="assets/구매하기.png" width="200" height="350" alt="구매 하기">
    <img src="assets/구매확정.png" width="200" height="350" alt="구매 확정">
    <img src="assets/내구매함.png" width="200" height="350" alt="내 구매함">
    <img src="assets/판매기능.png" width="200" height="350" alt="판매 기능">
    <img src="assets/판매내역.png" width="200" height="350" alt="판매 내역">
    <img src="assets/내구매함.png" width="200" height="350" alt="내 구매">
</div>

전체 프로토타입 -> [카카오 오븐 UI](https://ovenapp.io/view/N8q3JurAx3UZZR5DhCzkDvlEsCRUQnJZ/cFTi7)

## 📖 기능 목록 (설명 요약)

---

* **판매**
    * 등록 / 삭제
    * 목록 / 총 판매 금액 조회
* **구매**
    * 가격별 / 유효기간 별 조회
    * 구매 내역 조회(최근 구매순)
    * 구매하기
    * 구매 확정
* **회원**
    * 회원 가입 / 로그인 / 로그아웃
    * OAuth 로그인
    * 비밀번호 찾기 및 변경
    * 상품 좋아요
    * 계좌 등록

자세한 UseCase 👉 [Use Case (wiki)](https://github.com/f-lab-edu/givemeticon/wiki/Usecase)

## 🌐 서버 아키텍처

<img src="assets/서버 아키텍처.png">

---

## 🛠 Issue

* **GitHub Actions**를 활용하여 CI로 테스트 커버리지 확인하고, CD를 구축하여 비지니스 문제에만 집중하였습니다.
* **Spring Security** 없이 **OAuth** 로그인 구현하였습니다.
* **mybatis에** 하나의 데이터를 **insert** 하면 왜 "**1**"만 응답하던 것을 생성된 데이터의 id를 응답하게 하였습니다.
* 메일 전송 기능의 성능을 향상시키기 위해 **동기 방식**에서 5초가 걸리던 작업을 **비동기로 전환**하였습니다.
* 좋아요 비지니스 로직을 기존의 **토글 방식**에서 **테스트 용이성**과 좋아요 취소한 데이터가 무의미하다고 판단하여 **좋아요와 좋아요 취소 로직을 분리**하였습니다.

## 🤔 프로젝트를 진행하며 겪은 고민

* **Mapper**와 **Repository** 데이터 엑세스 계층 비교하여 **Mapper**만 사용한 아키텍처를 적용하였습니다.
* 여러 도메인 간의 결합을 낮추어 시스템의 모듈성을 향상시키고, 복잡성을 감소시켜 유지보수 및 확장을 용이하게 하기 위해 **Facade 패턴**을 적용하였습니다.
* **third-party**와 상호작용 하기 위해 스프링이 지원하는 기술 **RestTemplate**, **WebClient**, **FeignClient** 중 **간단하고 편리한 접근성**을 가진
  RestTemplate을 사용하였습니다.
* **효과적인 판매 시스템 설계**에 대한 고민과 구현을 다루며, SaleCreationFacade, SaleService, SaleCreateValidator의 **의존성을 효과적으로 관리**하고자 합니다. 코드
  리팩토링을 통해 **역할 분리**와 **순환 의존성**을 고려하여 **시스템의 응집성과 유지보수성을 강화**하였습니다.
* API 응답을 위한 ResponseDto는 도메인 객체 내부에 생성자를 두는 대신 도메인과 별도로 위치시켜 **의존성을 분리**하고, **단일 책임 원칙과 API 버전 관리**를 고려하여 **유지보수성**을
  높였습니다.