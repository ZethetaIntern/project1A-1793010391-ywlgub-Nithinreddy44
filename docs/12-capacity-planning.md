# Capacity Planning, Hardware Sizing & Cost Analysis: PayScale HTTPE

## 1. Executive Summary & Budget Boundary

Under Section A3.1 of the project specification, PayScale operates under an infrastructure budget constraint:
* **Legacy Infrastructure Spend:** $12,000 / month (supporting 1,200 TPS)
* **Target Budget Ceiling:** **$45,000 / month** (maximum permitted cloud expenditure)

This capacity planning analysis sizes compute, memory, storage IOPS, and network bandwidth across three operating scales:
1. **Scale 1 (Current Baseline):** 1,200 TPS (12M MAUs, 28M daily txns)
2. **Scale 2 (Target Diwali Peak):** 12,000 sustained TPS / 18,000 burst TPS (35M MAUs, 85M daily txns)
3. **Scale 3 (Future 2x Headroom):** 24,000 TPS (70M MAUs, 170M daily txns)

We establish that PayScale's scaled architecture operates at **$40,850 / month at 12,000 TPS**, saving $4,150/month beneath the strict $45,000 ceiling. With 3-year Compute Savings Plans and Reserved Instances, costs optimize down to **$28,450 / month**.

---

## 2. Infrastructure Sizing & Cost Breakdown at Target Scale (12,000 TPS)

The following sizing table strictly matches the reference architecture specified in Section C (Page 34) of the HTTPE specification:

| Service / Infrastructure Layer | Instances | Instance Type / Specs | CPU / Instance | Memory / Instance | Storage / Specs | Monthly Cost (USD) |
| :--- | :---: | :--- | :---: | :---: | :--- | :---: |
| **API Gateway Layer** | 4 | AWS `c6i.xlarge` | 4 vCPU | 8 GB | 50 GB gp3 | $1,200 |
| **Transaction Orchestrator** | 8 | AWS `c6i.xlarge` | 4 vCPU | 16 GB | 100 GB gp3 | $3,200 |
| **Payment Processing Service** | 12 | AWS `c6i.2xlarge` | 8 vCPU | 16 GB | 100 GB gp3 | $6,000 |
| **Account Service** | 6 | AWS `c6i.xlarge` | 4 vCPU | 8 GB | 100 GB gp3 | $1,800 |
| **Fraud Detection Service** | 4 | AWS `c6i.2xlarge` | 8 vCPU | 32 GB | 200 GB NVMe | $3,600 |
| **Notification Service** | 3 | AWS `c6i.large` | 2 vCPU | 4 GB | 50 GB gp3 | $450 |
| **Apache Kafka Cluster** | 3 | AWS `i3en.2xlarge` | 8 vCPU | 32 GB | 2.5 TB NVMe gp3 | $3,600 |
| **PostgreSQL Shards (4 Shards x 3 Nodes)**| 12 | AWS `r6i.2xlarge` | 8 vCPU | 64 GB | 500 GB gp3 @ 12K IOPS| $14,400 |
| **Redis Cluster (6 Nodes / 96 GB)**| 6 | AWS `r6g.xlarge` | 4 vCPU | 32 GB | In-memory | $4,800 |
| **Observability Stack (Prom/Graf/Loki)**| 3 | AWS `m6i.xlarge` | 4 vCPU | 16 GB | 1 TB gp3 | $1,800 |
| **TOTALS (12,000 TPS Target)** | **61** | — | **354 vCPU** | **1,348 GB** | **10.5 TB Storage** | **$40,850 / mo** |

* **Budget Ceiling:** $45,000 / month
* **Target Operating Margin:** **$4,150 / month (9.2% surplus headroom)**

---

## 3. Comparative Scaling Across 3 Workload Scales

| Architectural Component | Scale 1: Current Baseline (1,200 TPS) | Scale 2: Target Peak (12,000 TPS) | Scale 3: Future Scale (24,000 TPS) |
| :--- | :--- | :--- | :--- |
| **API Gateway** | 2 instances ($600/mo) | 4 instances ($1,200/mo) | 8 instances ($2,400/mo) |
| **Transaction Orchestrator** | 2 instances ($800/mo) | 8 instances ($3,200/mo) | 16 instances ($6,400/mo) |
| **Payment Service** | 4 instances ($2,000/mo) | 12 instances ($6,000/mo) | 24 instances ($12,000/mo) |
| **Account Service** | 2 instances ($600/mo) | 6 instances ($1,800/mo) | 12 instances ($3,600/mo) |
| **Fraud Detection Service** | 2 instances ($1,800/mo) | 4 instances ($3,600/mo) | 8 instances ($7,200/mo) |
| **Notification Service** | 1 instance ($150/mo) | 3 instances ($450/mo) | 6 instances ($900/mo) |
| **Kafka Cluster** | 3 brokers ($1,800/mo) | 3 brokers ($3,600/mo) | 6 brokers ($7,200/mo) |
| **PostgreSQL Database** | 1 Primary + 1 Standby ($2,400/mo) | 4 Shards x 3 Nodes ($14,400/mo) | 8 Shards x 3 Nodes ($28,800/mo) |
| **Redis Cache** | 1 node 16GB ($450/mo) | 6 nodes 96GB ($4,800/mo) | 12 nodes 192GB ($9,600/mo) |
| **Observability Stack** | 1 instance ($600/mo) | 3 instances ($1,800/mo) | 6 instances ($3,600/mo) |
| **Total Monthly Spend** | **$11,200 / month** | **$40,850 / month** | **$82,700 / month** |
| **Cost Per Million Transactions** | **$13.33 / M txns** | **$15.68 / M txns** | **$15.86 / M txns** |

---

## 4. Storage, Network & IOPS Sizing Calculations

### 4.1 Daily Storage Accumulation (85 Million Daily Transactions)
* Transaction record size: ~400 bytes
* Ledger journal entry size (2 per txn): $2 \times 250\text{ bytes} = 500\text{ bytes}$
* Outbox and event audit size: ~300 bytes
* **Total data generated per transaction:** $1.2\text{ KB}$
* **Daily Raw Storage Ingestion:**
  $$85,000,000 \times 1.2\text{ KB} \approx 102 \text{ GB / day} \quad (3.06 \text{ TB / month})$$
* Divided uniformly across 4 shards: $\approx 765\text{ GB per shard / month}$.
* Monthly range partitioning enables seamless automated compression and archival of partitions older than 90 days to Amazon S3 Glacier ($0.004/GB/mo), keeping active SSD working sets bounded.

### 4.2 Database IOPS Sizing
* At 12,000 TPS, 4 shards handle 3,000 write TPS each.
* With group commit and transactional outbox batching, each write requires ~2.5 physical disk IOPS.
* Disk IOPS required per shard: $3,000 \times 2.5 \approx 7,500\text{ IOPS}$.
* Provisioning AWS `gp3` volumes with **12,000 provisioned IOPS** per node provides a 60% performance safety margin, completely eliminating disk queue latency.

---

## 5. Cost Optimization Opportunities (Pathway to $28,450/month)

1. **3-Year Compute Savings Plans (42% Savings on Compute):**
   * Committing to baseline compute (Payment, Account, Orchestrator, Gateway) under AWS 3-Year All-Upfront Savings Plans reduces EC2 spend by 42%, saving **$6,800 / month**.
2. **PostgreSQL Reserved Instances (38% Savings on RDS/EC2):**
   * Purchasing 3-year Standard Reserved Instances for the 12 PostgreSQL database nodes reduces database hosting from $14,400 to **$8,900 / month** (saving $5,500/month).
3. **Spot Instances for Non-Critical Asynchronous Workers:**
   * Notification worker pods and reconciliation batch jobs operate statelessly with Kafka offset checkpointing. Running these workers on AWS Spot Instances (at a 70% discount) saves **$400 / month**.
4. **S3 Intelligent-Tiering for Compliance Logs:**
   * Archiving audit events and monthly partitions older than 30 days to S3 Glacier Deep Archive reduces long-term storage spend from $0.08/GB to $0.00099/GB.
* **Optimized Projected Spend:** **$27,750 / month** (A total savings of $13,100/month below baseline budget).
