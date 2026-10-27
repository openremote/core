# @openremote/or-vaadin-components

[![NPM Version][npm-image]][npm-url]

Package containing [Vaadin Web Components](https://vaadin.com/docs/latest/components), built with [Lit](https://lit.dev), with a slight adjustment to work within the OpenRemote ecosystem.
For documentation you can check out their [website](https://vaadin.com/docs/latest/components).

## Install

```bash
npm i @openremote/or-vaadin-components
yarn add @openremote/or-vaadin-components
```

## Usage

### JavaScript / TypeScript example using a Text Field

Note: using the `document.createElement()` API is an example here. Please check the documentation of your JavaScript framework for proper installation of Lit web components.

```typescript
import "@openremote/or-vaadin-components/or-vaadin-text-field";

// Load text field into the app
const textField = document.createElement("or-vaadin-text-field");
textField.setAttribute("label", "Company name");
textField.setAttribute("value", "OpenRemote");
document.body.appendChild(textField);
```

### Group fields

The group field `or-vaadin-checkbox-group` takes `vaadin-checkbox` children and `or-vaadin-radio-group` takes `or-vaadin-radio-button` children. These are registered when importing the group fields.

```html
<or-vaadin-checkbox-group>
  <vaadin-checkbox></vaadin-checkbox>
  <vaadin-checkbox></vaadin-checkbox>
</or-vaadin-checkbox-group>

<or-vaadin-radio-group>
  <or-vaadin-radio-button></or-vaadin-radio-button>
  <or-vaadin-radio-button></or-vaadin-radio-button>
</or-vaadin-radio-group>
```

## License

[GNU AGPL](https://www.gnu.org/licenses/agpl-3.0.en.html)

[npm-image]: https://img.shields.io/npm/v/@openremote/or-vaadin-components.svg
[npm-url]: https://www.npmjs.com/package/@openremote/or-vaadin-components
