import IVisibilityChanger from "../inteface/IVisibilityChanger"

export default class ShowVisibility implements IVisibilityChanger {
    public change(icon: HTMLImageElement, field: HTMLInputElement) : void {
        icon.src = "assets/show.png";
        icon.dataset.status = "show";
        field.type = "text";
    }
}