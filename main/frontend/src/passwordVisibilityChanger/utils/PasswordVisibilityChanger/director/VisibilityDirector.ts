import ShowVisibility from "../classes/ShowVisibility";
import IVisibilityChanger from "../inteface/IVisibilityChanger";

export default class VisibilityDirector {
    private _changer : IVisibilityChanger;

    constructor() {
        this._changer = new ShowVisibility();
    }

    public get changer() : IVisibilityChanger {
        return this._changer;
    }

    public set changer(value : IVisibilityChanger) {
        this._changer = value;
    }

    public change(icon: HTMLImageElement, field: HTMLInputElement) : void {
        this._changer.change(icon, field);
    }
}