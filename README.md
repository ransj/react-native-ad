# React Native Ad

React Native ad is an alternative version of official FB react native project with hightly improved performance.

# How To Improve Performance

RN use annotations(such as @ReactMethod、 @ReactProp) to tackle java modules and view modules. During runtime
RN need to find exported methods and view props by reflection. So this project improve performance by processing
annotations and other works during compile time, and directly invoke methods without frelection.

Supported RN versions
0.33
