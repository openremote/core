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
import { test } from "./fixtures/manager.js";

// A one-pixel yellow PNG, enlarged by the SVG to make missing embedded images visible.
const embeddedPng =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4/5/hPwAH/QL+ppTFtAAAAABJRU5ErkJggg==";
const styledSvg = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="120" height="20">
  <style>.sheet { fill: rgb(255, 0, 0) } .gradient { fill: url(#paint) }</style>
  <defs>
    <linearGradient id="paint">
      <stop offset="0%" stop-color="blue"/><stop offset="100%" stop-color="blue"/>
    </linearGradient>
    <clipPath id="clip"><rect x="40" width="20" height="20"/></clipPath>
    <rect id="shape" width="20" height="20" fill="magenta"/>
  </defs>
  <rect id="sheet" class="sheet" width="20" height="20"/>
  <rect id="inline" x="20" width="20" height="20" style="fill: rgb(0, 255, 0)"/>
  <rect class="gradient" x="40" width="20" height="20" clip-path="url(#clip)"/>
  <use href="#shape" x="60"/>
  <image href="${embeddedPng}" x="80" width="20" height="20"/>
  <image xlink:href="${embeddedPng}" x="100" width="20" height="20"/>
</svg>`;

let imageUrl: string;

test.beforeEach(async ({ manager, baseURL }) => {
  // Reuse one test filename so repeated runs do not accumulate uploaded images.
  const response = await manager.api.ConfigurationResource.imageUpload(
    { name: "configuration-csp.svg", contents: Buffer.from(styledSvg).toString("base64"), binary: true },
    { path: "test/configuration-csp.svg" },
    { ...(await manager.adminConfig()), responseType: "text" }
  );
  imageUrl = new URL(response.data, baseURL).href;
});

/**
 * @given An uploaded SVG uses styles and embedded PNG images
 * @when The image is opened directly
 * @then Its normal styling is preserved and its embedded images are permitted by CSP
 */
test("should preserve SVG styles and embedded images when opening an image directly", async ({ page }) => {
  const violations: string[] = [];
  page.on("console", (message) => {
    if (message.text().includes("Content Security Policy")) {
      violations.push(message.text());
    }
  });
  await page.goto(imageUrl);

  await expect(page.locator("#sheet")).toHaveCSS("fill", "rgb(255, 0, 0)");
  await expect(page.locator("#inline")).toHaveCSS("fill", "rgb(0, 255, 0)");
  // Without img-src data:, the endpoint CSP blocks otherwise valid embedded raster images.
  expect(violations).toEqual([]);
});

/**
 * @given An uploaded SVG uses styles, local references and embedded PNG images
 * @when It is displayed through an img element
 * @then All six regions render with their expected colors
 */
test("should render styled SVG images with local fragments and embedded rasters", async ({ page }) => {
  await page.goto(new URL("/api/master/configuration/manager", imageUrl).href);
  await page.setContent('<img id="logo">');
  await page.locator("#logo").evaluate((image, url) => image.setAttribute("src", url), imageUrl);

  await expect(page.locator("#logo")).toHaveJSProperty("naturalWidth", 120);
  const colors = await page.locator("#logo").evaluate((element) => {
    const image = element as HTMLImageElement;
    const canvas = document.createElement("canvas");
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext("2d")!;
    context.drawImage(image, 0, 0);
    return [10, 30, 50, 70, 90, 110].map((x) => Array.from(context.getImageData(x, 10, 1, 1).data));
  });

  // Preserve vector styling and embedded images referenced through both href and xlink:href.
  expect(colors).toEqual([
    [255, 0, 0, 255],
    [0, 255, 0, 255],
    [0, 0, 255, 255],
    [255, 0, 255, 255],
    [255, 255, 0, 255],
    [255, 255, 0, 255],
  ]);
});

const markExecution = "document.documentElement.setAttribute('data-executed', 'yes')";
const legacyDocuments = [
  {
    name: "SVG",
    contentType: "image/svg+xml",
    body: `<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" onload="${markExecution}">
      <script>${markExecution}</script>
      <script href="https://upload-attack.invalid/script.js"/>
      <style>
        @import url("https://upload-attack.invalid/style.css");
        rect { fill: url("https://upload-attack.invalid/paint.svg#paint") }
      </style>
      <image href="https://upload-attack.invalid/image.png" width="20" height="20"/>
      <rect width="20" height="20"/>
    </svg>`,
  },
  {
    name: "HTML",
    contentType: "text/html",
    body: `<!doctype html><html><head>
      <base href="https://upload-attack.invalid/">
      <link rel="stylesheet" href="https://upload-attack.invalid/style.css">
      <script src="https://upload-attack.invalid/script.js"></script>
      <script>${markExecution}</script>
      </head><body onload="${markExecution}">
      <img src="https://upload-attack.invalid/image.png">
      <iframe src="https://upload-attack.invalid/frame.html"></iframe>
      <form action="https://upload-attack.invalid/credentials"><button>Submit</button></form>
      </body></html>`,
  },
];

for (const document of legacyDocuments) {
  /**
   * @given Legacy content contains scripts and external resource references
   * @when It is opened directly with the image endpoint's response headers
   * @then Scripts, external requests, and access to application storage are blocked
   */
  test(`should isolate legacy ${document.name} image responses`, async ({ page }) => {
    const externalRequests: string[] = [];
    const violations: string[] = [];
    page.on("console", (message) => {
      if (message.text().includes("Content Security Policy")) {
        violations.push(message.text());
      }
    });
    await page.route("https://upload-attack.invalid/**", (route) => {
      externalRequests.push(route.request().url());
      return route.fulfill({ body: "Unexpected external request" });
    });
    await page.route(imageUrl, async (route) => {
      // Keep the real endpoint headers; substitute only content that predates upload validation.
      const response = await route.fetch();
      const headers = response.headers();
      delete headers["content-length"];
      delete headers["content-encoding"];
      headers["content-type"] = document.contentType;
      await route.fulfill({ response, headers, body: document.body });
    });

    await page.goto(imageUrl);
    if (document.name === "HTML") {
      await page.getByRole("button", { name: "Submit" }).click();
      expect(await page.evaluate(() => window.document.baseURI)).toBe(imageUrl);
      await expect(page).toHaveURL(imageUrl);
    }

    // These checks fail without the endpoint's CSP, even with nosniff and an img-based UI.
    await expect(page.locator(":root")).not.toHaveAttribute("data-executed", "yes");
    expect(externalRequests).toEqual([]);
    expect(violations.some((message) => message.includes("img-src"))).toBe(true);
    expect(
      await page.evaluate(() => {
        try {
          window.localStorage.getItem("configuration-csp-test");
          return false;
        } catch (error) {
          return error instanceof DOMException && error.name === "SecurityError";
        }
      })
    ).toBe(true);
  });
}
