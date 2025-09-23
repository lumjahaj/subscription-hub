# Subscription Hub

A **multi-tenant billing and subscription hub** built with **Spring Boot 3.5.6** and **PostgreSQL**.  
This is a portfolio project to demonstrate backend engineering skills: multitenancy, subscriptions, and billing.

---

## 🚀 Tech Stack
- Java 21 + Spring Boot **3.5.6**
- PostgreSQL 17 (Dockerized)
- pgAdmin 4 for database management
- Maven for builds
- Docker Compose for local setup

---

## ⚡ Quickstart

```bash
git clone https://github.com/lumjahaj/subscription-hub.git
cd subscription-hub
cp .env.example .env
docker compose up -d
```

---

## 🛠️ Getting Started

### 1. Clone the repo
```bash
git clone https://github.com/lumjahaj/subscription-hub.git
cd subscription-hub
```

### 2. Environment setup

Copy the example env file and create your own:

```bash
cp .env.example .env
```

### 3. Start services

```bash
docker compose up -d
```

### 4. Access

- **Postgres**
    - Host: `localhost`
    - Port: `5432`
    - Database: `subscription_hub_db`
    - Username: `subscriptionhub`
    - Password: `subscriptionhub`

- **pgAdmin**
    - URL: [http://localhost:8081](http://localhost:8081)
    - Email: `admin@local.com`
    - Password: `admin`
    - Host: `postgres`
    - Database: `subscription_hub_db`
    - Username: `subscriptionhub`
    - Password: `subscriptionhub`

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

