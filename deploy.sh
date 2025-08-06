./gradlew wasmJsBrowserDistribution
git checkout gh-pages
cp -r composeApp/build/dist/wasmJs/productionExecutable/* .
git add -A
git commit -m "deploy"
git push origin gh-pages
git checkout master
