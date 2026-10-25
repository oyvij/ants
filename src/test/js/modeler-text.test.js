// What the modeler writes as text and reads back, checked with no browser: `node --test`, run by ./gradlew test.
import {test} from "node:test"
import assert from "node:assert/strict"
import {camel, cronOf, linkedChanges, linkedProperties, operators, readCondition, readSchedule, resultFields, toCamunda, unary} from "../../main/resources/static/modeler-text.js"

test("every operator's JUEL reads back as the field, operator and value it was written from", () => {
    for (const op of Object.keys(operators)) {
        for (const value of unary.has(op) ? [""] : ["ship", "3", "-1.5", "it's", "a\\b", "x == y"]) {
            const written = operators[op]("review.output.verdict", value)
            assert.deepEqual(readCondition(written), {field: "review.output.verdict", op, value}, written)
        }
    }
})

test("a value is a number as is and a string quoted, with quotes and backslashes escaped", () => {
    assert.equal(operators.equals("a.output.n", "3"), "${a.output.n == 3}")
    assert.equal(operators["is more than"]("runs.draft", "2"), "${runs.draft > 2}")
    assert.equal(operators.equals("a.output.verdict", "ship"), "${a.output.verdict == 'ship'}")
    assert.equal(operators["does not equal"]("a.output.t", "it's \\"), "${a.output.t != 'it\\'s \\\\'}")
    assert.equal(operators["is not empty"]("a.output.items"), "${not empty a.output.items}")
})

test("a condition the builder didn't write isn't read, so it shows as is", () => {
    for (const body of ["", "${a && b}", "${a.output.n == ship}", "${a == 'x' || b}", "#{a}", "${a.output['k']}"]) {
        assert.equal(readCondition(body), null, body)
    }
})

test("a Schedule in words reads back from the cron it writes", () => {
    for (const schedule of [
        {every: "hour"},
        {every: "day", at: "08:00"},
        {every: "weekday", at: "07:30"},
        {every: "week", at: "23:05", on: "SUN"},
        {every: "month", at: "00:00", dom: "31"},
    ]) {
        const cron = cronOf(schedule)
        assert.deepEqual(readSchedule(cron), {...schedule, cron}, cron)
    }
})

test("the cron is seconds first, as Schedules.kt reads it", () => {
    assert.equal(cronOf({every: "hour"}), "0 0 * * * *")
    assert.equal(cronOf({every: "day", at: "08:00"}), "0 0 8 * * *")
    assert.equal(cronOf({every: "weekday", at: "07:30"}), "0 30 7 * * MON-FRI")
    assert.equal(cronOf({every: "week", at: "09:15"}), "0 15 9 * * MON")
    assert.equal(cronOf({every: "month", at: "06:00"}), "0 0 6 1 * *")
    assert.equal(cronOf({every: "advanced", cron: "0 */5 * * * *"}), "0 */5 * * * *")
})

test("any other cron is Advanced, kept as written", () => {
    for (const cron of ["0 */5 * * * *", "30 0 8 * * *", "0 0 8 * 1 *", "0 0 8 * * MON,WED", "0 0 24 * * *", "0 0 8 32 * *", "0 0 8 * *", "0 0 8 * * * 2027"]) {
        assert.deepEqual(readSchedule(cron), {every: "advanced", cron}, cron)
    }
})

test("a field's variable name is its label in camel case", () => {
    assert.equal(camel("Work branch"), "workBranch")
    assert.equal(camel("Repos"), "repos")
    assert.equal(camel("2 Ticket IDs!"), "ticketIds")
    assert.equal(camel(""), "")
})

test("a decision's condition reads the start form, the Results of the Tasks before it and their run counts, Ants' own variables and a multi-instance's item, each labelled by where it is from", () => {
    const offered = ["${prompt}", "${runs}", "${clones}", "${item}", "${draft.summary}", "${draft.output.verdict}", "${ask}", "${ask.approved}", "${build.draft.output.text}"]
    assert.deepEqual(resultFields(offered, {draft: "Draft", ask: "Ask", build: "Build"}, new Set(["draft", "ask"]), ["prompt"], ["runs", "clones", "sourceMap"]), [
        ["prompt", "from the start form"],
        ["draft.summary", "from Draft"], ["draft.output.verdict", "from Draft"], ["ask", "from Ask"], ["ask.approved", "from Ask"],
        ["build.draft.output.text", "from Build"], ["runs.draft", "times Draft ran"], ["runs.ask", "times Ask ran"],
        ["runs", "Ants' own"], ["clones", "Ants' own"], ["item", "a multi-instance's item"],
    ])
})

test("the diagram opens in camunda's namespace, which Ants' deploy path turns back", () => {
    assert.equal(toCamunda('<bpmn:definitions xmlns:operaton="http://operaton.org/schema/1.0/bpmn" operaton:topic="agent">'),
        '<bpmn:definitions xmlns:operaton="http://camunda.org/schema/1.0/bpmn" operaton:topic="agent">')
})

const catalogue = {
    nameBeside: "ants.name.",
    agents: [{id: "a-writer", name: "author"}, {id: null, name: "plain"}],
    skills: [{id: "k-draft", name: "drafting"}, {id: "k-tdd", name: "tdd"}],
}

test("an Agent Task's Agent and Skills read as their current names, found by id, else by the name kept beside the id, and no id shows", () => {
    const props = {"ants.agent": "a-writer", "ants.name.a-writer": "writer", "ants.skills": "k-draft, k-zz0001", "ants.name.k-draft": "draft",
        "ants.name.k-zz0001": "tdd", "ants.assignment": "Do it."}
    assert.deepEqual(linkedProperties(props, catalogue), {"ants.agent": "author", "ants.skills": "drafting, tdd", "ants.assignment": "Do it."})
    assert.deepEqual(linkedProperties({"ants.agent": "a-zz0002", "ants.name.a-zz0002": "gone"}, catalogue), {"ants.agent": "gone"})
    assert.deepEqual(linkedProperties({"ants.agent": "plain"}, catalogue), {"ants.agent": "plain"})
})

test("an Agent and Skills picked by name are written as ids, each current name beside its id, and the old names go", () => {
    const props = {"ants.agent": "a-old", "ants.name.a-old": "old", "ants.skills": "k-old", "ants.name.k-old": "old-skill"}
    assert.deepEqual(linkedChanges(props, {"ants.agent": "author", "ants.skills": "drafting, tdd"}, catalogue), {
        "ants.agent": "a-writer", "ants.skills": "k-draft, k-tdd", "ants.name.a-old": undefined, "ants.name.k-old": undefined,
        "ants.name.a-writer": "author", "ants.name.k-draft": "drafting", "ants.name.k-tdd": "tdd",
    })
    assert.deepEqual(linkedChanges({"ants.agent": "a-writer", "ants.name.a-writer": "writer"}, {"ants.assignment": "Go."}, catalogue),
        {"ants.assignment": "Go.", "ants.agent": "a-writer", "ants.name.a-writer": "author"})
    assert.deepEqual(linkedChanges({"ants.agent": "a-writer", "ants.skills": "k-draft", "ants.name.k-draft": "drafting"}, {"ants.skills": undefined}, catalogue),
        {"ants.skills": undefined, "ants.name.k-draft": undefined, "ants.agent": "a-writer", "ants.name.a-writer": "author"})
    assert.deepEqual(linkedChanges({}, {"ants.agent": "plain"}, catalogue), {"ants.agent": "plain"})
})

test("a link that matches nothing here keeps its id and the name beside it when the Task is written again", () => {
    const props = {"ants.agent": "a-zz0002", "ants.name.a-zz0002": "gone", "ants.skills": "k-zz0003", "ants.name.k-zz0003": "lost"}
    assert.deepEqual(linkedChanges(props, {"ants.assignment": "Go."}, catalogue),
        {"ants.assignment": "Go.", "ants.name.a-zz0002": "gone", "ants.name.k-zz0003": "lost", "ants.agent": "a-zz0002", "ants.skills": "k-zz0003"})
})
