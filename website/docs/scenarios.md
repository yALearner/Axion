# Scenarios

Eight real-world enterprise use cases where Axion provides the Agent runtime foundation.

## 01 — Banking Compliance Assistant

Agents auto-review transaction logs and push real-time alerts on anomalies. Every tool call and LLM request is recorded in audit tables, satisfying CBIRC regulatory requirements.

## 02 — Government Approval Automation

Agents integrate with government system APIs to auto-verify documents and generate approval drafts. Private deployment ensures citizen data never leaves the government cloud, with full operational traceability.

## 03 — Telecom Network Operations

Ops Agents run scheduled inspection scripts, auto-create incident tickets, and notify on-call engineers. Shell whitelist ensures only authorized operational commands can execute.

## 04 — Energy Equipment Monitoring

Agents pull sensor data via HTTP Tool and push alerts via notify Tool on anomalies. Local SQLite stores complete historical records; works offline during network outages.

## 05 — Medical Imaging Assistance

Agents call imaging analysis MCP Servers for preliminary screening, writing results to hospital systems. All data stays within the hospital intranet, meeting HIPAA and personal information protection requirements.

## 06 — Manufacturing Quality Inspection

Agents read production line log files, call QC model APIs, and auto-generate inspection reports. Cron scheduler triggers runs automatically — no manual initiation needed.

## 07 — Financial Risk Control

Agents connect to risk databases via MCP Server and analyze transaction patterns in real-time. Memory module retains risk preferences across sessions, automatically escalating review levels for suspicious transactions.

## 08 — Retail Intelligent Support

Agents connect to WeCom Channel to handle returns, exchanges, and order queries. `save_memory` remembers customer preferences for future conversations. Full audit trail for every interaction.
