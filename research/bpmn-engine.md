# Which BPMN and DMN engine runs Ants?

Research for [oyvij/ants#2](https://github.com/oyvij/ants/issues/2). Checked on 2026-10-09 against
vendor docs, legal pages, GitHub repos, crates.io and Docker Hub. Each claim cites the source it
came from. Inferences are marked as such.

## Answer

**Operaton**, run as its standalone Java distribution (or the Docker image), on a file-backed
database, with Ants' Agents as external-task workers that call its REST API. It is the only
candidate that meets every must in the question **and** is free for open-ended daily use without
asking anyone. Camunda 8 is the better engine on paper (task headers, a first-party Rust SDK), but
its licence does not cover a long-lived service a user relies on every day.

## The must-haves, per candidate

| | Camunda 8.10 (Zeebe) self-managed | Operaton 2.1 / 2.2 | Camunda 7 CE 7.24 | Flowable OSS 8.0 | Own engine on bpmn-js |
|---|---|---|---|---|---|
| Licence for Ants | Free for dev/test only; production needs Enterprise or an *approved* non-commercial licence | Apache-2.0, no editions | Apache-2.0, but EOL and repo archived | Apache-2.0 | MIT (bpmn-engine), bpmn.io licence (bpmn-js) |
| Footprint | JVM 21+, ~505 MB image; H2/Postgres instead of Elasticsearch | JVM 17-25, ~290 MB image; H2 file DB by default, Postgres later | JVM, same as Operaton | JVM, ~172 MB REST image | Node process, no JVM |
| Worker API | Job workers over gRPC or REST | External tasks over REST, long polling | External tasks over REST | External worker jobs over REST | Whatever we write |
| Rust | Official SDK, **technical preview** (0.3.1) | Plain REST; one small community crate | Plain REST | Plain REST | n/a |
| User tasks / XOR / call activity | Yes / Yes / Yes | Yes / Yes / Yes | Yes / Yes / Yes | Yes / Yes / Yes | Partial, ours to finish |
| DMN | Built in (business rule task) | Built in | Built in | Built in | Bring your own |
| Custom Task properties | `zeebe:taskHeaders`, handed to the worker with the job | `camunda:`/`operaton:` extension properties, fetched with `includeExtensionProperties` | Same as Operaton | `flowable:` attributes; the Camunda Modeler does not write them | Any moddle extension we define |
| Survives restart | Yes: on-disk log, snapshots, RocksDB | Yes: transactional relational DB | Yes, same | Yes: relational DB | Only if we persist `getState()` ourselves |
| Drawable in Camunda Modeler | Yes (C8 target) | Yes (C7 target) | Yes (C7 target) | Shapes only, no properties panel | Yes (plain BPMN) |

## Camunda 8 (Zeebe), self-managed

**Licence: the blocker.** From 8.6 on, Zeebe, Operate, Tasklist, Identity and Optimize ship as
compiled software under a proprietary licence: "either the Camunda Self-Managed Non-Production
license or the Enterprise Edition license". "To use the software in production, purchase the Camunda
Self-Managed Enterprise Edition." The source is under the Camunda License v1.
([docs: Licenses](https://docs.camunda.io/docs/reference/licenses/)). The Non-Production Terms
(v1.6, effective 1 May 2024) grant a "revocable right ... solely in non-production environments",
defined as "development, testing, quality assurance, demonstration, or evaluation purposes, and not
for any business, commercial, operational, or production purposes". Either party may end it on
ten days' notice ([Camunda legal: Self-Managed Non-Production
Terms](https://legal.camunda.com/licensing-and-other-legal-terms)). Ants is a service the user
leaves running overnight to do real work. *Inference:* that is "operational" use, not evaluation.

A way out exists, but it needs approval. Camunda's non-commercial programme lists "Hobby project
(i.e. for personal use only, not in professional work environment)", and eligibility "will depend
on the specific use case, as well as your organization". You apply through a form
([Camunda: C8 SM Non-Commercial License](https://camunda.com/pricing/c8sm-non-commercial-license/)).
*Inference:* if Ants ever drives repos the user works on for an employer, it falls outside
"not in professional work environment".

**Footprint.** Camunda 8 Run (c8run) is the local distribution for macOS, Linux and Windows. It
bundles the Orchestration Cluster and Connectors, and uses H2 as default secondary storage, so no
Elasticsearch is needed. It is "not intended for production" ([docs: Camunda 8
Run](https://docs.camunda.io/docs/self-managed/quickstart/developer-quickstart/c8run/)). It needs
OpenJDK 21-25 ([docs: install and
start](https://docs.camunda.io/docs/self-managed/quickstart/developer-quickstart/c8run/install-start/)).
On a server, PostgreSQL can replace Elasticsearch for Operate and Tasklist. Only Optimize still
needs Elasticsearch/OpenSearch, H2 is for development only, and you cannot migrate between the two
backends later ([docs: relational
databases](https://docs.camunda.io/docs/next/self-managed/concepts/databases/relational-db/)). The
`camunda/camunda:latest` image is about 505 MB (Docker Hub, 2026-10-06). The current release is
8.10.2 (2026-10-07, [GitHub camunda/camunda](https://github.com/camunda/camunda/releases)).

**Worker API.** Official SDKs: Java, Spring Boot, TypeScript, Python and C#. Rust, Go and PHP are
"technical preview" ([docs: APIs and
tools](https://docs.camunda.io/docs/apis-tools/working-with-apis-tools/)). The Rust SDK is
`camunda-orchestration-sdk` 0.3.1 (crates.io, 2026-09-28), built on the Orchestration Cluster REST
API. It "may still evolve before it is declared fully supported. Pin a specific version"
([GitHub camunda/orchestration-cluster-api-rust](https://github.com/camunda/orchestration-cluster-api-rust)).
A handler returns `JobAction` complete / fail / BPMN error ([docs: Rust job
workers](https://docs.camunda.io/docs/apis-tools/rust-sdk/job-workers/)). The older community
crates (`zeebe` 0.4.2, last updated 2022) are stale.

**BPMN/DMN.** The coverage table lists user tasks, call activities, exclusive gateways, business
rule tasks, multi-instance, embedded and event subprocesses. The standard "Loop" marker is listed
with no documentation link ([docs: BPMN
coverage](https://docs.camunda.io/docs/components/modeler/bpmn/bpmn-coverage/)). *Inference:* build
loops as a gateway with a back-edge, which works on every engine here. DMN runs inside the engine
through `zeebe:calledDecision`, or the business rule task can go to a job worker instead ([docs:
business rule tasks](https://docs.camunda.io/docs/components/modeler/bpmn/business-rule-tasks/)).

**Custom Task properties.** `zeebe:taskDefinition type="..."` picks the worker. `zeebe:taskHeaders`
carry "static metadata handed to workers along with the job" ([docs: service
tasks](https://docs.camunda.io/docs/components/modeler/bpmn/service-tasks/)). This fits Ants
exactly: `agent=griller`, `skill=grilling`. Element templates can bind fields to `zeebe:taskHeader`
([bpmn-io/bpmn-js-element-templates](https://github.com/bpmn-io/bpmn-js-element-templates)), so an
"Agent Task" can show up in the Modeler as its own element with Agent and Skill fields.

**Durability.** Each partition is "a persistent append-only event stream" ([docs:
partitions](https://docs.camunda.io/docs/components/zeebe/technical-concepts/partitions/)). Brokers
keep the event log, a periodic snapshot of in-flight state, and a RocksDB projection on disk ([docs:
resource
planning](https://docs.camunda.io/docs/self-managed/zeebe-deployment/operations/resource-planning/)).

## Operaton (community fork of Camunda 7)

**Licence.** Apache-2.0, "no open-core model, no commercial editions", community-driven, and backed
by several companies ([GitHub operaton/operaton](https://github.com/operaton/operaton)). Very
active: 2.1.5 and 2.2.0-RC1 came out 2026-09-18, and 2.2 is planned for October 2026 with 6 months
of support. 2.1 support ends October 2026 ([operaton.org/roadmap](https://operaton.org/roadmap)).
*Inference:* minor versions get only six months of support, so Ants has to follow upgrades every
half year.

**Footprint.** JVM 17-25. Ships as Docker (`docker run -p 8080:8080 operaton/operaton:latest`, about
290 MB) or as a standalone "run" distribution, embeddable in Spring Boot or Quarkus. It supports H2,
PostgreSQL, MySQL, MariaDB, Oracle, SQL Server and DB2 (README). The run distribution defaults to a
**file-backed** H2 database (`jdbc:h2:./operaton-h2-default/process-engine`, from
[distro/run/assembly/resources/default.yml](https://github.com/operaton/operaton/blob/main/distro/run/assembly/resources/default.yml)),
so a laptop install needs no separate database. Moving to a server means swapping in Postgres. It
needs no Elasticsearch.

**Worker API.** External tasks: a service task with `type="external"` and a `topic`. Workers call
`fetchAndLock` with a lock duration, and the REST API long-polls until work arrives. The owner can
call `extendLock`. Workers report `complete`, `handleFailure` (retries; at 0 an incident is raised)
or `handleBpmnError`, which a boundary event catches ([Operaton docs: external
tasks](https://docs.operaton.org/docs/documentation/user-guide/process-engine/external-tasks/)).
Extension properties can come back with the fetch (`includeExtensionProperties` in
`ExternalTaskQueryTopicBuilder` and the Java client, per the source code). The only official client
is Java (`clients/java`). Any language works over REST. Rust: a small community crate,
`operaton-task-worker` 0.4.0 (crates.io, 2026-06-10). *Inference:* `reqwest` plus a fetch-and-lock
loop is a few dozen lines, so Ants can skip the crate.

**BPMN/DMN.** It carries Camunda 7's engine forward unchanged, with "fully compatible REST API,
database schema, and deployable BPMN/DMN models" (README). That means user tasks (with Tasklist
and REST), exclusive gateways, call activities, multi-instance, and DMN through `engine-dmn`.

**Custom Task properties.** `<camunda:properties><camunda:property name=... value=.../>` or the
`operaton:` equivalent. The parser accepts both namespaces: `new Namespace(OPERATON_BPMN_EXTENSIONS_NS,
CAMUNDA_BPMN_EXTENSIONS_NS)` in `BpmnParse.java`. However, the `camunda:` constant is marked
`@Deprecated(forRemoval = true, since = "1.0")` in
[`BpmnParser.java`](https://github.com/operaton/operaton/blob/main/engine/src/main/java/org/operaton/bpm/engine/impl/bpmn/parser/BpmnParser.java).
The Camunda Desktop Modeler still targets Camunda 7. Its changelog fixes C7 issues as recently as
the 5.5x line, and v5.52.0 shipped 2026-10-06, MIT licensed
([GitHub camunda/camunda-modeler](https://github.com/camunda/camunda-modeler)). C7 element templates
can bind `camunda:property`
([bpmn-io/bpmn-js-element-templates](https://github.com/bpmn-io/bpmn-js-element-templates)). An
Operaton Modeler exists, "ported from Fluxnova Modeler", but it has **no releases**, so you build it
from source ([GitHub operaton/operaton-modeler](https://github.com/operaton/operaton-modeler)).

**Durability.** All Run state lives in the relational database and is committed at wait states
(user tasks, external tasks). An external task whose lock expires during a restart becomes
fetchable again (external task docs above). *Inference:* if an Agent is mid-`claude -p` when Ants
restarts, the engine re-offers the Task once the lock lapses. Ants has to decide whether to resume
the session or start fresh, which issue #1 already plans for.

## Camunda 7 Community Edition

EOL: the final CE release was 7.24.0 (October 2025), and "no further security patches or
maintenance updates" follow. Enterprise patches run to April 2030
([camunda.com/platform-7](https://camunda.com/de/platform-7/); [forum
announcement](https://forum.camunda.io/t/important-update-camunda-7-community-edition-end-of-life-announced/50921)).
`camunda/camunda-bpm-platform` is **archived**, with its last push on 2025-11-04 (GitHub API).
Technically it is the same engine as Operaton, minus a future. Reject it: Operaton is the same
thing, maintained.

## Flowable OSS

Apache-2.0, active (8.0.0 released 2026-02-27, [GitHub
flowable/flowable-engine](https://github.com/flowable/flowable-engine/releases)), with BPMN, CMMN
and DMN. It has external worker jobs over REST, with official clients for Java, JS, Python, .NET and
Go (repos under [github.com/flowable](https://github.com/flowable)). There is no Rust client. Two
problems for Ants:
- **Modeling.** Flowable 7 removed the open-source Modeler and UI apps. The replacement designer is
  in Flowable's cloud trial ([Flowable 7.0.0 release
  post](https://flowable.com/blog/releases/flowable-open-source-7-0-0-release)). The Camunda
  Modeler draws the shapes but writes `camunda:`/`zeebe:` attributes, which Flowable does not read.
  *Inference:* every Agent/Skill property would be hand-edited XML or need a custom bpmn-js
  properties provider.
- **No user-task app** in OSS any more, the same release post says. Ants needs its own UI anyway
  (the Notification list), so this one matters less.

## A small engine of our own on bpmn-js / dmn-js

The nearest base is `bpmn-engine` (MIT, npm 26.0.5, active). It "only support[s] elements and
attributes included in the BPMN 2.0 scheme, but can be extended", and has call activity and loop
docs, but no DMN and no worker queue
([GitHub paed01/bpmn-engine](https://github.com/paed01/bpmn-engine)). DMN would come from `feelin`
(FEEL in JS, MIT) or DecisionToolkit `dsntk` (Rust, Apache-2.0, 27 stars). Durability, job locking,
retries, incidents, timers and a user-task API would all be Ants code.
bpmn-js is MIT plus a clause that the bpmn.io watermark "MUST NOT be removed"
([bpmn-js LICENSE](https://github.com/bpmn-io/bpmn-js/blob/develop/LICENSE)).
*Inference:* this trades a JVM for months of building a less reliable engine. It is only worth it
if a JVM on the laptop is a hard no.

## Others looked at and dropped

- **Kogito / jBPM:** `apache/incubator-kie-kogito-runtimes` is archived and has "moved to
  incubator-kie-drools" (GitHub API). Too unsettled.
- **SpiffWorkflow / spiff-arena:** Python, BPMN and DMN, active (v4.0.0, 2026-09-22), but LGPL. It
  also has its own `spiffworkflow` modeler extensions, not Camunda's, and service tasks go through a
  connector proxy, not an external-worker queue
  ([GitHub sartography/spiff-arena](https://github.com/sartography/spiff-arena)).
- **Fluxnova** (FINOS fork of Camunda 7, Apache-2.0, v2.0.5 / v3.0.1): a credible second C7 fork,
  younger and smaller (102 stars against Operaton's 488)
  ([GitHub finos/fluxnova-bpm-platform](https://github.com/finos/fluxnova-bpm-platform)). Worth
  knowing as a fallback if Operaton stalls, since both read the same models.

## Recommendation and the trade-off

**Use Operaton.** Draw in the Camunda Desktop Modeler with the Camunda 7 target. Mark Agent Tasks as
external service tasks with one topic (say `agent`), and put Agent and Skill on each Task as
extension properties. Ship an element template so they appear as fields. Ants' Rust service
long-polls `fetchAndLock` over REST with `includeExtensionProperties`, runs `claude -p`, and extends
the lock while the session lives. Ants' Notification list reads and completes user tasks through
the REST API. Locally: the run distribution on its file H2. On a server: the same, on Postgres.

**What this gives up:** Camunda 8 is the more modern engine. Task headers are cleaner than
extension properties, it has an official (preview) Rust SDK, and it is where Camunda's Modeler and
element-template work is headed. Operaton costs a JVM, a six-monthly upgrade cadence, and a
dependency on a community project. It also leans on the Modeler's C7 target, which Camunda keeps
alive only while C7 Enterprise is supported (to 2030), and on the `camunda:` namespace, which
Operaton has marked for removal.
Both risks can be undone: the Operaton Modeler and a one-time `camunda:` to `operaton:` rename
cover them. Camunda 8's risk cannot: its licence makes Ants' everyday, overnight use either a
breach or a request Camunda can refuse, and the terms are revocable on ten days' notice. If the
user gets Camunda's non-commercial licence in writing, the choice flips to Camunda 8. Nothing else
in this research argues against it.
