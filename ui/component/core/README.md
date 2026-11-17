# @openremote/core

[![NPM Version][npm-image]][npm-url]

ES6 modules for connecting to an OpenRemote Manager as well as utilities for performing common tasks.

The default export is a singleton of type `Manager` that can be used to connect to an OpenRemote Manager, initiate
authentication and download common resources (translation files, icons, etc). Everything is initiated by calling
the `init` method, what tasks are performed during initialization is determined by the `ManagerConfig` passed to the
`init`, the tasks include the following:

- Check the manager exists and is accessible (calls the `api/master/info` endpoint)
- Initialize authentication and perform login redirect (if requested in the `ManagerConfig`)
- Download `mdi` iconset (if requested in the `ManagerConfig` - if not specified iconset will be downloaded)
- Initialize REST API client (`@openremote/rest`) - Sets a timeout of 10s and will also add a request interceptor to
  add required `Authorization` header for authentication
- Initialize console (the console is the device used to render the application desktop, Android or iOS device)
- Download built in OpenRemote translation files
- Download Asset Model Descriptors

## Install

```bash
npm i @openremote/core
yarn add @openremote/core
```

## Usage

For a full list of properties, methods and options refer to the TypeDoc generated [documentation](<>).

Initialization is done by calling the `init` method which returns a Promise that is fulfilled with a `boolean` indicating
whether initialization was successful or not.

Initialization usage example:

```$javascript
import openremote from "@openremote/core";

openremote.init({
    managerUrl: "http://127.0.0.1:8080",
    keycloakUrl: "http://127.0.0.1:8081/auth",
    auth: Auth.KEYCLOAK,
    autoLogin: false,
    realm: "building",
    configureTranslationsOptions: (options) => {
        options.lng = "nl"; // Change initial language to dutch rather than english
    }
}).then((success) => {
    if (success) {
        // Load the app
    } else {
        // Something has gone wrong
    }
});
```

### Asset Mixin (`dist/asset-mixin`)

Exports a `subscribe` function/mixin that can be used by components to connect to one or more Assets in the OpenRemote
Manager; it takes care of subscribing to events for the specified Asset(s), usage example:

```$javascript
class AssetComponent extends subscribe(openremote) {

    constructor() {
        this.assetIds = [this.asset];
    }

    // Override this method to be notified when an attribute event is received for a subscribed asset. This is called
    // whenever an attribute's value is modified.
    public onAttributeEvent(event: AttributeEvent) {}

    // Override this method to be notified when an asset event is received for a subscribed asset. This is called when
    // an asset is first subscribed or when an asset is modified (attribute value changes are handled as attribute events)
    public onAssetEvent(event: AssetEvent) {}

    // If you need to modify an attribute then call the sendAttributeEvent method; the event must be for a subscribed asset.
    doSendEvent(event: AttributeEvent) {
        this.sendAttributeEvent(event);
    }
}
```

### Events (`./dist/event`)

Provides infrastructure for connecting to the OpenRemote Manager client event bus; by default an `EventProvider` instance
`Manager` is initialized by the `Manager` during the initialization process and can be accessed from `openremote.events`
but it is also possible to instantiate an `EventProvider` manually.

### Util (`./dist/util`)

Various utility methods for common tasks.

## Supported Browsers

The last 2 versions of all modern browsers are supported, including Chrome, Safari, Opera, Firefox, Edge.

## License

[GNU AGPL](https://www.gnu.org/licenses/agpl-3.0.en.html)

[npm-image]: https://img.shields.io/npm/v/@openremote/core.svg
[npm-url]: https://www.npmjs.com/package/@openremote/core
