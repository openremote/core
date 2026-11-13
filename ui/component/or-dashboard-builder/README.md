# or-dashboard-builder

### All-in-one bundle for creating dashboards with OpenRemote data.

This component is the core of the Insights page on our OpenRemote manager,<br />
where users can build dashboards with widgets, for monitoring their assets in their preferred way.<br />
It is quite an extensive piece of code, so some apps using this (such as or standalone Insights app)<br />
might not need the full package.<br />
<br />
It is structured to support the additions of custom widgets, whereof a short tutorial is shown below.

---

## Install

```bash
npm i @openremote/or-dashboard-builder
yarn add @openremote/or-dashboard-builder
```

## Terminology:

To be clear about what all functionalities are meant for, we created a list of terms used within `or-dashboard-builder`.
Here it is:

| Term              | Definition                                                                                                                                       |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Dashboard**     | The board / area that contains widgets. (stored in database)                                                                                     |
| **Widget**        | The object on the dashboard that contains information like content (stored in database)                                                          |
| _WidgetContainer_ | Wrapper that displays widget content, header, and its actions                                                                                    |
| _WidgetSettings_  | The available settings to customize WidgetConfig,                                                                                                |
| **Insights**      | 'The showcase term' we use for the page in our Manager App. We also have an "Insights app" that is a standalone app for viewing your dashboards. |

<br/>

Here is an overview of all elements used:

| HTML Tag                    | Description                                                                      |
| --------------------------- | -------------------------------------------------------------------------------- |
| or-dashboard-builder        | Contains the layout of the full dashboard builder. Keeps track of state as well. |
| or-dashboard-preview        | Manages grid, and the widgets loaded onto it.                                    |
| or-dashboard-browser        | Lists widget(s) in cards that can be dragged onto `or-dashboard-preview`         |
| or-dashboard-tree           | Lists dashboards                                                                 |
| or-dashboard-boardsettings  | Managing dashboard settings                                                      |
| or-dashboard-widgetsettings | Wrapper that displays settings of the selected widget (`or-widget-settings`)     |
| or-widget-container         | Manages the loaded widget in that container                                      |
| or-widget-settings          | Loads and saves widget settings based on the selected widget.                    |

_or-dashboard-engine is a logic class to inject in Gridstack for overriding grid behavior._<br />
_or-dashboard-keyhandler is used to handle keystrokes from the user_
<br />
<br />

## Creating your own Widget

Add your widget manifest to the `registerWidgetTypes()` function in `index.ts`.<br />
This will register the widget, and handle all functions automatically.

```typescript
export function registerWidgetTypes() {
    widgetTypes.set("linechart", ChartWidget.getManifest());
    widgetTypes.set("gauge", GaugeWidget.getManifest());
    ...
    // add here
}
```

From there, you can add your custom class to the `/widgets` folder and build your HTMLElements.<br />
It is **required** to inherit from `or-widget`, _(or an extended class of it such as or-asset-widget)_<br />
and your custom config should extend on `WidgetConfig`<br />
<br />

### Example of a custom Widget

Here is a code example of how to create custom widgets.<br />
Feel free to copy, put it in separate files, and adjust it to your needs.<br />
Looking into our existing widgets also helps understanding the codebase.

```typescript
import { CustomWidgetConfig } from "./custom-widget";

export interface CustomWidgetConfig extends WidgetConfig {
  attributeRefs: AttributeRef[];
  customFieldOne: string;
  customFieldTwo: number;
}

function getDefaultWidgetConfig(): CustomWidgetConfig {
  return {
    attributeRefs: [],
    customFieldOne: "default text",
    customFieldTwo: 0,
  };
}

@customElement("custom-widget")
export class CustomWidget extends OrWidget {
  // Override of widgetConfig with extended type
  protected readonly widgetConfig!: CustomWidgetConfig;

  static getManifest(): WidgetManifest {
    return {
      displayName: "Custom widget", // name to display in widget browser
      displayIcon: "gauge", // icon to display in widget browser. Uses <or-icon> and https://materialdesignicons.com
      minColumnWidth: 1,
      minColumnHeight: 1,
      getContentHtml(config: CustomWidgetConfig): OrWidget {
        return new CustomWidget(config);
      },
      getSettingsHtml(config: CustomWidgetConfig): WidgetSettings {
        return new CustomSettings(config);
      },
      getDefaultConfig(): CustomWidgetConfig {
        return getDefaultWidgetConfig();
      },
    };
  }

  public refreshContent(force: boolean) {
    // function that executes on refresh of the widget.
    // It's normally a 'silent' function that, for example, fetches the data of assets again.
  }

  protected render(): TemplateResult {
    return html`
      <span>Custom field one: </span>
      <span>${this.widgetConfig.customFieldOne}</span>
    `;
  }
}

// Settings element
// This can be placed in a seperate file if preferred.
@customElement("custom-settings")
export class CustomSettings extends WidgetSettings {
  // Override of widgetConfig with extended type
  protected readonly widgetConfig!: CustomWidgetConfig;

  protected render(): TemplateResult {
    return html`
      <span>Custom settings</span>
      <button @click="${() => this.onButtonClick()}">Click to customize text</button>
    `;
  }

  protected onButtonClick() {
    this.widgetConfig.customFieldOne = "custom text";
    this.notifyConfigUpdate();
  }
}
```
