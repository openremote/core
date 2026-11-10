/*
 * Copyright 2026, OpenRemote Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { expect } from "@openremote/test";
import { test, userStatePath } from "./fixtures/manager.js";
import { preparedAssetsForRules as assets } from "./fixtures/data/assets.js";
import { energyRule } from "./fixtures/data/rules.js";
import { type Asset, type RealmRuleset, RulesetLang } from "@openremote/model";
import type { OrRuleTree } from "@openremote/or-rules/or-rule-tree";

test.use({ storageState: userStatePath });

function createRealmRule(rule: Partial<RealmRuleset> & Pick<RealmRuleset, "id" | "name" | "lang">): RealmRuleset {
  return {
    type: "realm",
    id: rule.id,
    version: rule.version ?? 0,
    realm: rule.realm ?? "smartcity",
    enabled: rule.enabled ?? true,
    name: rule.name,
    lang: rule.lang,
    rules: rule.rules ?? "SomeRulesCode",
    meta: rule.meta,
    status: rule.status,
    error: rule.error,
    accessPublicRead: rule.accessPublicRead,
  };
}

async function mockRealmRulesApi(page: any, initialRules: RealmRuleset[]) {
  let rules = [...initialRules];
  let deleteRequests = 0;
  let postRequests = 0;
  let putRequests = 0;

  await page.route("**/api/**/rules/realm**", async (route: any) => {
    const request = route.request();
    const method = request.method();
    const url = new URL(request.url());

    if (method === "GET") {
      if (/\/rules\/realm\/\d+$/.test(url.pathname)) {
        const id = Number(url.pathname.split("/").pop());
        const rule = rules.find((r) => r.id === id);
        await route.fulfill(rule ? { status: 200, json: rule } : { status: 404, json: { message: "Not found" } });
        return;
      }

      await route.fulfill({ status: 200, json: rules });
      return;
    }

    if (method === "DELETE") {
      deleteRequests += 1;
      const id = Number(url.pathname.split("/").pop());
      rules = rules.filter((rule) => rule.id !== id);
      await route.fulfill({ status: 204, body: "" });
      return;
    }

    if (method === "POST") {
      postRequests += 1;
      await route.fulfill({ status: 500, json: { message: "Unexpected create request" } });
      return;
    }

    if (method === "PUT") {
      putRequests += 1;
      await route.fulfill({ status: 500, json: { message: "Unexpected update request" } });
      return;
    }

    await route.fallback();
  });

  return {
    get deleteRequests() {
      return deleteRequests;
    },
    get postRequests() {
      return postRequests;
    },
    get putRequests() {
      return putRequests;
    },
    get rules() {
      return rules;
    },
  };
}

/**
 * Simple function that generates assets based on {@link assets}
 * @param multiplier - Amount of assets to generate per asset type
 */
function generateALotOfAssets(multiplier = 5) {
  return Array.from({ length: multiplier }, (_, i) =>
    assets.map((a: Asset) => ({ ...a, name: `${a.name} ${i}` }))
  ).flat();
}

/**
 * @when Creating a When-Then rule
 * @and Naming the rule
 * @and Configuring a When condition on the asset
 * @and Configuring a Then action on the same asset
 * @and Saving the rule
 * @then The When-Then rule should appear in the rule list
 */
test("Create a When-Then rule for an asset with a trigger and action", async ({ page, manager, rulesPage, shared }) => {
  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await rulesPage.goto();
  await rulesPage.createRule("When-Then");
  await rulesPage.setRuleName(energyRule.name);

  // When clause
  const when = page.locator("or-rule-when");
  await rulesPage.configureAttributeWhenClause(when, {
    assetType: energyRule.asset_type,
    asset: energyRule.asset,
    attribute: energyRule.attribute_when,
    operator: "Less than or equal to",
    value: energyRule.value.toString(),
  });

  // Then clause
  const then = page.locator("or-rule-then-otherwise");
  await then.getByRole("menuitem", { name: "Add action" }).click();
  await then.getByRole("menuitem", { name: energyRule.asset_type }).click();
  await then.getByRole("combobox", { name: "Asset", exact: true }).click();
  await then.getByRole("option", { name: energyRule.asset, exact: true }).click();
  await then.getByRole("combobox", { name: "Attribute", exact: true }).click();
  await then.getByRole("option", { name: energyRule.attribute_then, exact: true }).click();
  await then.getByRole("spinbutton", { name: "Value" }).fill(energyRule.value.toString());

  await shared.interceptResponse<number>("**/rules/realm", (rule) => {
    if (rule) manager.rules.push(rule);
  });

  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator(`text=${energyRule.name}`)).toHaveCount(1);
});

/**
 * @when Creating a When-Then rule
 * @and Naming the rule
 * @and Configuring a When condition by searching for a specific asset
 * @and Configuring a Then action by searching for a different asset
 * @and Saving the rule
 * @then The When-Then rule should appear in the rule list
 */
test("Create a When-Then rule by searching for an asset", async ({ page, manager, rulesPage, shared }) => {
  const multiplier = 200; // Using a multiplier above 100, which is the default querying limit
  const aLotOfAssets = generateALotOfAssets(multiplier);
  await manager.setup("smartcity", { assets: aLotOfAssets });
  await manager.goToRealmStartPage("smartcity");
  await rulesPage.goto();

  // Make sure the correct amount assets are set up, and the variables for this rule are adjusted
  expect(manager.assets.length).toBe(multiplier * assets.length);
  const firstAssetName = [...manager.assets].find((a) => a.name?.includes(energyRule.asset))?.name ?? "";
  const lastAssetName = [...manager.assets].reverse().find((a) => a?.name?.includes(energyRule.asset))?.name ?? "";
  expect(firstAssetName).toContain(energyRule.asset);
  expect(lastAssetName).toContain(energyRule.asset);

  await rulesPage.createRule("When-Then");
  await rulesPage.setRuleName(energyRule.name);

  // Select asset type of the When clause, search for the last asset in the list, and select the attribute
  const when = page.locator("or-rule-when");
  await rulesPage.configureAttributeWhenClause(when, {
    assetType: energyRule.asset_type,
    asset: lastAssetName,
    attribute: energyRule.attribute_when,
    operator: "Less than or equal to",
    value: energyRule.value.toString(),
  });

  // Configure Then clause
  const then = page.locator("or-rule-then-otherwise");
  await then.getByRole("menuitem", { name: "Add action" }).click();
  await then.getByRole("menuitem", { name: energyRule.asset_type }).click();
  await then.getByRole("combobox", { name: "Asset", exact: true }).fill(firstAssetName);
  await then.getByRole("option", { name: firstAssetName, exact: true }).click();
  await then.getByRole("combobox", { name: "Attribute", exact: true }).fill(energyRule.attribute_then);
  await then.getByRole("option", { name: energyRule.attribute_then, exact: true }).click();
  await then.getByRole("spinbutton", { name: "Value" }).fill(energyRule.value.toString());

  await shared.interceptResponse<number>("**/rules/realm", (rule) => {
    if (rule) manager.rules.push(rule);
  });

  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator(`text=${energyRule.name}`)).toHaveCount(1);
});

/**
 * @when Creating a When-Then rule
 * @and Naming the rule
 * @and Configuring a When condition on the asset
 * @and Configuring a Then action with an email notification
 * @and Saving the rule
 * @then The When-Then rule should appear in the rule list
 */
test("Create a When-Then rule for an asset with a email notification action", async ({
  page,
  manager,
  rulesPage,
  shared,
}) => {
  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await rulesPage.goto();
  await rulesPage.createRule("When-Then");
  await rulesPage.setRuleName(energyRule.name);

  // When clause
  const when = page.locator("or-rule-when");
  await rulesPage.configureAttributeWhenClause(when, {
    assetType: energyRule.asset_type,
    asset: energyRule.asset,
    attribute: energyRule.attribute_when,
    operator: "Less than or equal to",
    value: energyRule.value.toString(),
  });
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();

  // Then clause
  const then = page.locator("or-rule-then-otherwise");
  await then.getByRole("menuitem", { name: "Add action" }).click();
  await then.getByRole("menuitem", { name: "Email" }).click();
  await expect(page.getByRole("button", { name: "Save", exact: true })).not.toBeDisabled();

  await then.getByRole("combobox", { name: "Recipients", exact: true }).click();
  await then.getByRole("option", { name: "Users", exact: true }).click();
  await then.getByRole("combobox", { name: "Users", exact: true }).click();
  await then.getByRole("option", { name: "Linked", exact: true }).click();
  await then.getByRole("button", { name: "Message", exact: true }).click();

  // Set up notification message in dialog
  const overlay = rulesPage.actionDialogOverlay(then);
  await expect(overlay).toBeVisible();
  await then.getByRole("textbox", { name: "Subject", exact: true }).clear();
  await rulesPage.blurActiveField(then);
  await expect(then.getByRole("textbox", { name: "Subject", exact: true })).toHaveAttribute("invalid");
  await expect(then.getByRole("button", { name: "Ok", exact: true })).toBeDisabled();

  await then.getByRole("textbox", { name: "Subject", exact: true }).fill("Email notification");
  await rulesPage.blurActiveField(then);
  await expect(then.getByRole("textbox", { name: "Subject", exact: true })).not.toHaveAttribute("invalid");
  await expect(then.getByRole("button", { name: "Ok", exact: true })).not.toBeDisabled();

  await then
    .getByRole("textbox", { name: "Body", exact: true })
    .fill("Assets that have been impacted: %TRIGGER_ASSETS%");
  await then.getByRole("button", { name: "Ok", exact: true }).click();

  await shared.interceptResponse<number>("**/rules/realm", (rule) => {
    if (rule) manager.rules.push(rule);
  });

  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.locator(`text=${energyRule.name}`)).toHaveCount(1);
});

/**
 * @when Creating a When-Then rule
 * @and Naming the rule
 * @and Configuring a When condition on the asset
 * @and Configuring a Then action with an push notification
 * @and Saving the rule
 * @then The When-Then rule should appear in the rule list
 */
test("Create a When-Then rule for an asset with a push notification action", async ({
  page,
  manager,
  rulesPage,
  shared,
}) => {
  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await rulesPage.goto();
  await rulesPage.createRule("When-Then");
  await rulesPage.setRuleName(energyRule.name);

  // When clause
  const when = page.locator("or-rule-when");
  await rulesPage.configureAttributeWhenClause(when, {
    assetType: energyRule.asset_type,
    asset: energyRule.asset,
    attribute: energyRule.attribute_when,
    operator: "Less than or equal to",
    value: energyRule.value.toString(),
  });
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();

  // Then clause
  const then = page.locator("or-rule-then-otherwise");
  await then.getByRole("menuitem", { name: "Add action" }).click();
  await then.getByRole("menuitem", { name: "Push notification" }).click();
  await expect(page.getByRole("button", { name: "Save", exact: true })).not.toBeDisabled();

  await then.getByRole("button", { name: "Message", exact: true }).click();

  // Set up notification message in dialog
  const overlay = rulesPage.actionDialogOverlay(then);
  await expect(overlay).toBeVisible();
  await then.getByRole("textbox", { name: "Title", exact: true }).fill("Push notification");
  await then.getByRole("textbox", { name: "Body", exact: true }).clear();
  await rulesPage.blurActiveField(then);
  await expect(then.getByRole("textbox", { name: "Body", exact: true })).toHaveAttribute("invalid");
  await expect(then.getByRole("button", { name: "Ok", exact: true })).toBeDisabled();

  await then.getByRole("textbox", { name: "Body", exact: true }).fill("Impacted assets: %TRIGGER_ASSETS%");
  await rulesPage.blurActiveField(then);
  await expect(then.getByRole("textbox", { name: "Body", exact: true })).not.toHaveAttribute("invalid");
  await expect(then.getByRole("button", { name: "Ok", exact: true })).not.toBeDisabled();

  // Fill in website URL
  await then.getByRole("textbox", { name: "Website to be opened", exact: true }).fill("https://openremote.io");
  await expect(then.getByRole("textbox", { name: "Body", exact: true })).not.toHaveAttribute("invalid");
  await expect(then.getByRole("button", { name: "Ok", exact: true })).not.toBeDisabled();

  // Toggle the switch for opening in browser
  await then.getByRole("switch", { name: "Open in browser (for external websites)", exact: true }).click();
  await expect(then.getByRole("textbox", { name: "Body", exact: true })).not.toHaveAttribute("invalid");
  await expect(then.getByRole("button", { name: "Ok", exact: true })).not.toBeDisabled();

  await then.getByRole("button", { name: "Ok", exact: true }).click();

  await shared.interceptResponse<number>("**/rules/realm", (rule) => {
    if (rule) manager.rules.push(rule);
  });

  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.locator(`text=${energyRule.name}`)).toHaveCount(1);
});

/**
 * @when Creating a When-Then rule
 * @and Naming the rule
 * @and Configuring a When condition on the asset
 * @and Configuring a Then action with an push notification
 * @and Saving the rule
 * @then The When-Then rule should appear in the rule list
 */
test("Create a When-Then rule for an asset with a alarm action", async ({ page, manager, rulesPage, shared }) => {
  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await rulesPage.goto();
  await rulesPage.createRule("When-Then");
  await rulesPage.setRuleName(energyRule.name);

  // When clause
  const when = page.locator("or-rule-when");
  await rulesPage.configureAttributeWhenClause(when, {
    assetType: energyRule.asset_type,
    asset: energyRule.asset,
    attribute: energyRule.attribute_when,
    operator: "Less than or equal to",
    value: energyRule.value.toString(),
  });
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();

  // Then clause
  const then = page.locator("or-rule-then-otherwise");
  await then.getByRole("menuitem", { name: "Add action" }).click();
  await then.getByRole("menuitem", { name: "Alarm" }).click();
  await expect(page.getByRole("button", { name: "Save", exact: true })).not.toBeDisabled();

  await then.getByRole("button", { name: "Severity" }).click();
  await then.getByRole("option", { name: "High", exact: true }).click();
  await then.getByRole("button", { name: "Settings", exact: true }).click();

  // Set up notification message in dialog
  const overlay = rulesPage.actionDialogOverlay(then);
  await expect(overlay).toBeVisible();
  await then.getByRole("textbox", { name: "Title", exact: true }).fill("High priority alarm");
  await then.getByRole("textbox", { name: "Content", exact: true }).clear();
  await rulesPage.blurActiveField(then);
  await expect(then.getByRole("textbox", { name: "Content", exact: true })).toHaveAttribute("invalid");
  await expect(then.getByRole("button", { name: "Ok", exact: true })).toBeDisabled();

  await then.getByRole("textbox", { name: "Content", exact: true }).fill("Warning: %TRIGGER_ASSETS%");
  await rulesPage.blurActiveField(then);
  await expect(then.getByRole("textbox", { name: "Content", exact: true })).not.toHaveAttribute("invalid");
  await expect(then.getByRole("button", { name: "Ok", exact: true })).not.toBeDisabled();

  // Adjust assignee
  await then.getByRole("combobox", { name: "Assignee", exact: true }).click();
  await then.getByRole("option", { name: "smartcity", exact: true }).click();
  await expect(then.getByRole("button", { name: "Ok", exact: true })).not.toBeDisabled();
  await then.getByRole("button", { name: "Ok", exact: true }).click();

  await shared.interceptResponse<number>("**/rules/realm", (rule) => {
    if (rule) manager.rules.push(rule);
  });

  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.locator(`text=${energyRule.name}`)).toHaveCount(1);
});

/**
 * @given A When-Then rule with an alarm action
 * @when Setting the alarm severity, which sits outside the settings dialog
 * @and Editing the alarm title inside the dialog and cancelling
 * @then The severity should be kept, and the saved rule should not contain the cancelled edit
 */
test("Cancelling the alarm settings dialog discards only the changes made inside it", async ({
  page,
  manager,
  rulesPage,
  shared,
}) => {
  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await rulesPage.goto();
  await rulesPage.createRule("When-Then");
  await rulesPage.setRuleName(energyRule.name);

  // When clause
  const when = page.locator("or-rule-when");
  await rulesPage.configureAttributeWhenClause(when, {
    assetType: energyRule.asset_type,
    asset: energyRule.asset,
    attribute: energyRule.attribute_when,
    operator: "Less than or equal to",
    value: energyRule.value.toString(),
  });

  // Then clause, with an alarm that is filled in and confirmed
  const then = page.locator("or-rule-then-otherwise");
  await then.getByRole("menuitem", { name: "Add action" }).click();
  await then.getByRole("menuitem", { name: "Alarm" }).click();
  await then.getByRole("button", { name: "Settings", exact: true }).click();
  await then.getByRole("textbox", { name: "Title", exact: true }).fill("Kept title");
  await then.getByRole("textbox", { name: "Content", exact: true }).fill("Kept content");
  await rulesPage.blurActiveField(then);
  await then.getByRole("button", { name: "Ok", exact: true }).click();

  // Severity is edited outside of the dialog
  await then.getByRole("button", { name: "Severity" }).click();
  await then.getByRole("option", { name: "High", exact: true }).click();

  // Reopen the dialog, edit the title, and back out again
  await then.getByRole("button", { name: "Settings", exact: true }).click();
  await then.getByRole("textbox", { name: "Title", exact: true }).fill("Discarded title");
  await rulesPage.blurActiveField(then);
  await then.getByRole("button", { name: "Cancel", exact: true }).click();

  // Cancel used to restore a snapshot of the whole action, undoing the severity picked outside it
  await expect(then.getByRole("button", { name: "Severity" })).toContainText("High");

  let saved: RealmRuleset | undefined;
  await shared.interceptResponse<number>("**/rules/realm", (rule, request) => {
    if (rule) manager.rules.push(rule);
    saved = request?.postDataJSON();
  });

  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.locator(`text=${energyRule.name}`)).toHaveCount(1);

  // Cancel used to swap the element's own action reference, leaving the rule holding the edit
  const action = JSON.parse(saved!.rules!).rules[0].then[0];
  expect(action.alarm.title).toBe("Kept title");
  expect(action.alarm.severity).toBe("HIGH");
});

/**
 * @when Creating a When-Then rule
 * @and Naming the rule
 * @and Configuring a When condition on the asset
 * @and Configuring a Then action with an push notification
 * @and Saving the rule
 * @then The When-Then rule should appear in the rule list
 */
test("Create a When-Then rule for an asset with a webhook action", async ({ page, manager, rulesPage, shared }) => {
  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await rulesPage.goto();
  await rulesPage.createRule("When-Then");
  await rulesPage.setRuleName(energyRule.name);

  // When clause
  const when = page.locator("or-rule-when");
  await rulesPage.configureAttributeWhenClause(when, {
    assetType: energyRule.asset_type,
    asset: energyRule.asset,
    attribute: energyRule.attribute_when,
    operator: "Less than or equal to",
    value: energyRule.value.toString(),
  });
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();

  // Then clause
  const then = page.locator("or-rule-then-otherwise");
  await then.getByRole("menuitem", { name: "Add action" }).click();
  await then.getByRole("menuitem", { name: "Webhook" }).click();
  await then.getByRole("button", { name: "Message", exact: true }).click();

  // Set up notification message in dialog
  const overlay = rulesPage.actionDialogOverlay(then);
  await expect(overlay).toBeVisible();
  await expect(then.getByRole("button", { name: "Ok", exact: true })).toBeDisabled();
  await then.getByRole("button", { name: "Method" }).first().click();
  await then.getByRole("option", { name: "PUT", exact: true }).click();
  await expect(then.getByRole("button", { name: "Ok", exact: true })).toBeDisabled();
  await then.getByRole("textbox", { name: "Web URL", exact: true }).fill("https://localhost");
  await rulesPage.blurActiveField(then);
  await expect(then.getByRole("button", { name: "Ok", exact: true })).not.toBeDisabled();

  // Add request header
  await then.getByRole("button", { name: "Add Request Header" }).click();
  await then.getByRole("textbox", { name: "Header", exact: true }).fill("key");
  await then.getByRole("textbox", { name: "Value", exact: true }).fill("value");

  // Add authorization info
  await then.getByRole("switch", { name: "Requires Authorization", exact: true }).click();
  await then.getByRole("button", { name: "Method" }).last().click();
  await then.getByRole("option", { name: "oauth Client Credentials Grant", exact: true }).click();
  await then.getByRole("textbox", { name: "Token URL", exact: true }).fill("https://localhost/token");
  await then.getByRole("textbox", { name: "Client ID", exact: true }).fill("client");
  await then.getByRole("textbox", { name: "Client secret", exact: true }).fill("secret");

  // Remove body
  await then.getByRole("switch", { name: "Include body in Request", exact: true }).click();
  await expect(then.locator("or-vaadin-text-area")).not.toBeVisible(); // The payload is the form's only text area

  // Close the dialog
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();
  await expect(then.getByRole("button", { name: "Ok", exact: true })).not.toBeDisabled();
  await then.getByRole("button", { name: "Ok", exact: true }).click();

  await shared.interceptResponse<number>("**/rules/realm", (rule) => {
    if (rule) manager.rules.push(rule);
  });

  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.locator(`text=${energyRule.name}`)).toHaveCount(1);
});

/**
 * @when Creating a Flow rule
 * @and Naming the rule
 * @and Dragging elements onto the canvas
 * @and Assigning attributes and values
 * @and Connecting elements together
 * @and Saving the rule
 * @then The Flow rule should appear in the rule list
 */
test("Create a Flow rule for an asset with logic connections", async ({ page, shared, rulesPage, manager }) => {
  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await rulesPage.goto();
  await rulesPage.createRule("Flow");
  await rulesPage.setRuleName("Solar panel");

  await page.locator(".node-item.input-node", { hasText: "Attribute value" }).hover();
  await shared.drag(450, 250);
  await page.hover("text=Number");
  await shared.drag(450, 350);
  await page.hover("text=Number");
  await shared.drag(450, 500);
  await page.hover("text=Number");
  await shared.drag(450, 600);
  await page.hover("text=>");
  await shared.drag(650, 300);
  await page.hover("text=Number switch");
  await shared.drag(800, 425);
  await page.locator(".node-item.output-node", { hasText: "Attribute value" }).hover();
  await shared.drag(1000, 425);

  await page.getByRole("button", { name: "Attribute" }).nth(0).click();
  await page.getByRole("alertdialog").getByText("Solar Panel").click();
  await page.getByRole("option", { name: "Power", exact: true }).click();
  await page.getByRole("button", { name: "Add" }).click();

  await page.getByRole("button", { name: "Attribute" }).nth(1).click();
  await page.getByRole("alertdialog").getByText("Solar Panel").click();
  await page.getByRole("option", { name: "Power forecast", exact: true }).click();
  await page.getByRole("button", { name: "Add" }).click();

  await page.fill('[placeholder="value"] >> nth=0', "50");
  await page.fill('[placeholder="value"] >> nth=1', "60");
  await page.fill('[placeholder="value"] >> nth=2', "40");

  await page.dragAndDrop(".socket >> nth=0", ".socket-side.inputs flow-node-socket .socket >> nth=0");
  await page.dragAndDrop(
    "flow-node:nth-child(2) .socket-side flow-node-socket .socket",
    "flow-node-socket:nth-child(2) .socket"
  );
  await page.dragAndDrop(
    "div:nth-child(3) flow-node-socket .socket",
    " flow-node:nth-child(6) .socket-side.inputs flow-node-socket .socket >> nth=0"
  );
  await page.dragAndDrop(
    "flow-node:nth-child(3) .socket-side flow-node-socket .socket",
    "flow-node:nth-child(6) .socket-side.inputs flow-node-socket:nth-child(2)"
  );
  await page.dragAndDrop(
    "flow-node:nth-child(4) .socket-side flow-node-socket .socket",
    "flow-node-socket:nth-child(3) .socket"
  );
  await page.dragAndDrop(
    "flow-node:nth-child(6) .socket-side.outputs flow-node-socket .socket",
    "flow-node:nth-child(7) .socket-side flow-node-socket .socket"
  );

  await shared.interceptResponse<number>("**/rules/realm", (rule) => {
    if (rule) manager.rules.push(rule);
  });

  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator("or-rule-tree").getByText("Solar panel")).toHaveCount(1);
});

test("Legacy JavaScript rules are visible but read-only in the rules UI", async ({ page, manager }) => {
  const legacyJavascriptRule = createRealmRule({
    id: 71001,
    name: "Legacy JS rule",
    lang: RulesetLang.JAVASCRIPT,
    rules: "console.log('legacy');",
  });
  const api = await mockRealmRulesApi(page, [legacyJavascriptRule]);

  await manager.setup("smartcity");
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Rules");

  await expect(page.locator("or-rule-tree").getByText(legacyJavascriptRule.name!)).toHaveCount(1);
  await page.locator("or-rule-tree").getByText(legacyJavascriptRule.name!).click();

  await expect(page.locator("or-rule-viewer")).toContainText(
    "JavaScript rules are legacy and can only be viewed or deleted."
  );
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();
  await expect(page.locator("or-rule-tree").locator("or-mwc-input[icon='content-copy']")).toHaveCount(0);

  await page.click(".mdi-plus >> nth=0");
  await expect(page.getByRole("menuitem", { name: "JavaScript", exact: true })).toHaveCount(0);

  expect(api.postRequests).toBe(0);
  expect(api.putRequests).toBe(0);
});

test("Legacy JavaScript rules can still be deleted from the rules UI", async ({ page, manager }) => {
  const legacyJavascriptRule = createRealmRule({
    id: 71002,
    name: "Legacy JS deletable rule",
    lang: RulesetLang.JAVASCRIPT,
    rules: "console.log('delete me');",
  });
  const api = await mockRealmRulesApi(page, [legacyJavascriptRule]);

  await manager.setup("smartcity");
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Rules");

  await page.locator("or-rule-tree").getByText(legacyJavascriptRule.name!).click();
  await page
    .locator("or-rule-tree")
    .getByRole("button")
    .filter({ has: page.locator('*[icon="delete"]') })
    .click();
  await page.getByRole("button", { name: "Delete" }).click();

  await expect(page.locator("or-rule-tree").getByText(legacyJavascriptRule.name!)).toHaveCount(0);
  expect(api.deleteRequests).toBe(1);
});

test("Groups containing legacy JavaScript rules cannot be renamed", async ({ page, manager }) => {
  const groupedLegacyJavascriptRule = createRealmRule({
    id: 71003,
    name: "Legacy JS grouped rule",
    lang: RulesetLang.JAVASCRIPT,
    meta: { groupId: "Legacy JS group" },
    rules: "console.log('grouped');",
  });
  const api = await mockRealmRulesApi(page, [groupedLegacyJavascriptRule]);

  await manager.setup("smartcity");
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Rules");

  await expect(page.locator("or-rule-tree").getByText("Legacy JS group", { exact: true })).toHaveCount(1);
  await page.locator("or-rule-tree").getByText("Legacy JS group", { exact: true }).click();
  await page.getByRole("textbox", { name: "Group name" }).fill("Renamed JS group");
  await page.getByRole("button", { name: "Save" }).click();
  expect(api.putRequests).toBe(0);
});

test("Dragging a legacy JavaScript rule is blocked", async ({ page, manager }) => {
  const legacyJavascriptRule = createRealmRule({
    id: 71004,
    name: "Legacy JS drag rule",
    lang: RulesetLang.JAVASCRIPT,
    rules: "console.log('drag');",
  });
  const api = await mockRealmRulesApi(page, [legacyJavascriptRule]);

  await manager.setup("smartcity");
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Rules");

  await expect(page.locator("or-rule-tree").getByText(legacyJavascriptRule.name!)).toHaveCount(1);

  const dispatchSucceeded = await page.locator("or-rules").evaluate((element, ruleName) => {
    const tree = element.shadowRoot?.querySelector("or-rule-tree") as OrRuleTree;
    const rule = tree?.rules?.find((candidate: any) => candidate.name === ruleName);

    if (!rule) {
      throw new Error(`Could not find ruleset '${ruleName}' in tree state`);
    }

    return element.dispatchEvent(
      new CustomEvent("or-tree-drag", {
        bubbles: true,
        composed: true,
        cancelable: true,
        detail: {
          nodes: [{ id: String(rule.id), label: rule.name, ruleset: rule }],
          groupNode: { id: "Target Group", label: "Target Group", children: [] },
          newNodes: [],
        },
      })
    );
  }, legacyJavascriptRule.name!);

  expect(dispatchSucceeded).toBe(false);
  await expect(page.locator("or-mwc-snackbar")).toContainText("JavaScript rules are legacy and cannot be updated.");
  expect(api.putRequests).toBe(0);
});

test.afterEach(async ({ manager }) => {
  await manager.cleanUp();
});
